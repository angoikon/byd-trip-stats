package com.byd.tripstats.sdk

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * Load the real OEM `bydauto` classes at runtime by injecting the already-installed
 * `com.byd.data.collect` APK into our OWN app classloader, instead of requiring a
 * locally-regenerated `dilink5-sdk.jar` to be bundled into the build.
 *
 * Why our own loader (not createPackageContext / a child DexClassLoader): the D3-shared data path
 * SUBCLASSES the OEM abstract listeners (AbsBYDAutoChargingListener etc.), and a subclass must share
 * one classloader with its superclass. Appending the OEM apk to this app's PathClassLoader.dexElements
 * makes `android.hardware.bydauto.*` resolve through the same loader that defines our subclasses.
 *
 * Safe to call before consent/exemption exists: on failure this just leaves the classes unresolvable,
 * and every touch-point (chargingListener/gearboxListener/tyreListener are `by lazy`; Dilink5Client's
 * reflective binds go through tryDevice{}) already tolerates that — same as it does today when the
 * OEM SDK simply isn't present. Call BEFORE the first tryDevice{} block in start() runs.
 *
 * NOTE: touches hidden dalvik.system members (pathList / makePathElements) → needs the hidden-API
 * exemption widened to include `Ldalvik/system/` (see AdbPermissionManager.VEHICLE_API_SETTINGS).
 */
object Dilink5SdkInjector {
    private const val TAG = "Dilink5SdkInjector"
    // Every class BydVehicleDataSource/Dilink5Client directly subclass (compile-time supertype
    // reference, eagerly link-checked) must be probed, not just one. Probing Statistic alone let a
    // coincidental/stale resolution of THAT ONE class make ensure() return true and skip injection
    // entirely — leaving Tyre/Charging permanently unresolvable (NoClassDefFoundError on first touch,
    // cached as a permanent failure by the classloader for the rest of the process — confirmed
    // on-car: deterministic on every fresh launch, not a transient race).
    private val PROBE_CLASSES = listOf(
        "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
        "android.hardware.bydauto.charging.AbsBYDAutoChargingListener",
        "android.hardware.bydauto.tyre.AbsBYDAutoTyreListener",
        "android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener",
    )
    private const val OEM_PKG = "com.byd.data.collect"

    // Only latches (skips future attempts within this process) once we're CONFIDENT retrying won't
    // help — i.e. the OEM package genuinely isn't installed. A reflection/permission failure during
    // the actual injection attempt is usually transient (e.g. ensure() ran before the hidden-API
    // exemption took effect on a pre-consent first launch) and is allowed to retry on the next call.
    @Volatile private var permanentlyUnavailable = false

    // Snapshot of dexElements from BEFORE our first injection on this loader, captured once and
    // reused on every re-inject instead of re-reading the CURRENT array. ensure() is called
    // repeatedly (start/pre-Charging/pre-Tyre/recoverEventDelivery) and is a no-op whenever
    // loadable() already holds — but if a later call finds loadable() false again (e.g. the
    // mutation didn't hold), rebuilding from whatever the array currently looks like would append
    // a FRESH set of elements on top of whatever's already there each time, growing the array
    // across every such re-inject. Rebuilding from this pristine snapshot instead keeps the result
    // the same size (pristine + one fresh set) no matter how many times ensure() retries.
    private var pristineLoader: ClassLoader? = null
    private var pristineDexElements: Array<*>? = null

    /** True if the bydauto classes are available on this app's classloader (already, or after inject). */
    @Synchronized
    fun ensure(context: Context): Boolean {
        val loader = context.classLoader
        if (loadable(loader)) return true             // already present (e.g. bundled jar)
        if (permanentlyUnavailable) return false

        val apkPaths = oemApkPaths(context)
        if (apkPaths.isEmpty()) {
            Log.w(TAG, "$OEM_PKG not found / no apk path")
            permanentlyUnavailable = true              // won't change without a fresh install; stop retrying
            return false
        }

        return try {
            val baseCl = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListF = baseCl.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList = pathListF.get(loader)
            val dexListCls = pathList.javaClass
            val dexElementsF = dexListCls.getDeclaredField("dexElements").apply { isAccessible = true }
            val old = dexElementsF.get(pathList) as Array<*>
            val base = if (pristineLoader === loader) pristineDexElements!! else old.also {
                pristineLoader = loader
                pristineDexElements = it
            }

            // Load classes.dex fully in-memory (dalvik.system.DexPathList#makeInMemoryDexElements),
            // the same primitive backing the public InMemoryDexClassLoader, instead of pointing a
            // File-based Element at any apk path. A file-based Element — even a byte-identical copy
            // made into our own writable dir — turned out to make NO difference: ART keys its OAT/vdex
            // reuse off the DEX CONTENT CHECKSUM, not the file path, so an identical copy of
            // /system/app/BydDataCollect/BydDataCollect.apk still resolved to the SAME pre-baked oat
            // compiled against BydDataCollect's own original (system-app) classloader context — and
            // silently dropped whichever classes' verification needs didn't match that foreign
            // context (Charging/Tyre; Statistic/Instrument happened to still resolve). An in-memory
            // dex has no backing file and therefore no checksum-keyed oat/vdex to reuse at all: ART
            // must fully parse/verify it fresh, under THIS classloader, every time.
            val suppressed = ArrayList<IOException>()
            val newEls = makeInMemoryElements(dexListCls, apkPaths, suppressed)
                ?: makeElements(dexListCls, apkPaths.map { File(it) },
                    File(context.codeCacheDir, "bydauto-inj").apply { mkdirs() }, suppressed)
                ?: return false.also { Log.w(TAG, "no dex-element builder found (in-memory or file-based)") }
            suppressed.forEach { Log.w(TAG, "suppressed: $it") }

            // APPEND (reverted from an earlier prepend attempt — confirmed crashing on-car). Dex
            // elements are searched in order, first match wins. com.byd.data.collect bundles its OWN
            // (R8-minified) copy of AndroidX Lifecycle etc., whose short obfuscated class names (e.g.
            // androidx.lifecycle.o) can collide with our own app's real classes of the same name.
            // Prepending meant data.collect's incompatible copy won those collisions, which crashed
            // MainActivity.onCreate with NoSuchMethodError whenever the classloader mutation happened
            // to run (via the service, racing an abrupt process restart) before our own copy of that
            // class had been resolved/cached. Appending means OUR classes are always checked first
            // for ANY name collision, independent of that race — bydauto/* is the only thing OEM
            // actually needs to supply, and compileOnly + dexdump verification (see PR discussion)
            // already confirm no bydauto class exists in our own apk to be shadowed by this ordering.
            val comp = base.javaClass.componentType
            val combined = java.lang.reflect.Array.newInstance(comp, base.size + newEls.size)
            System.arraycopy(base, 0, combined, 0, base.size)
            System.arraycopy(newEls, 0, combined, base.size, newEls.size)
            dexElementsF.set(pathList, combined)

            val ok = loadable(loader)
            Log.i(TAG, "injected ${newEls.size} dex element(s); bydauto loadable=$ok")
            com.byd.tripstats.util.DiagLog.event(context.applicationContext, TAG, "injected; bydauto loadable=$ok")
            ok
            // NOT latched here even on failure: a thrown SecurityException/NoSuchFieldException etc.
            // usually means the hidden-API exemption isn't active yet, which can resolve without a
            // fresh process (e.g. a later ensure() call after consent lands mid-session).
        } catch (t: Throwable) {
            Log.w(TAG, "inject failed: ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    private fun loadable(loader: ClassLoader): Boolean =
        PROBE_CLASSES.all { runCatching { Class.forName(it, false, loader) }.isSuccess }

    private fun oemApkPaths(context: Context): List<String> = runCatching {
        val ai = context.packageManager.getApplicationInfo(OEM_PKG, 0)
        buildList {
            ai.sourceDir?.let { add(it) }
            ai.splitSourceDirs?.let { addAll(it) }
        }.distinct()
    }.getOrDefault(emptyList())

    // Extract every classesN.dex from each source apk and hand them to DexPathList's in-memory
    // element builder (added alongside InMemoryDexClassLoader, API 26+ — always present on our
    // min/target here). Returns null (not an empty array) if the method itself can't be found, so
    // the caller falls back to the file-based path instead of silently injecting zero elements.
    private fun makeInMemoryElements(
        dexListCls: Class<*>, apkPaths: List<String>, suppressed: MutableList<IOException>
    ): Array<*>? {
        val m = runCatching {
            dexListCls.getDeclaredMethod(
                "makeInMemoryDexElements", Array<ByteBuffer>::class.java, List::class.java
            ).apply { isAccessible = true }
        }.getOrNull() ?: return null

        val buffers = apkPaths.flatMap { path ->
            runCatching {
                ZipFile(path).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                        .map { entry -> ByteBuffer.wrap(zip.getInputStream(entry).readBytes()) }
                        .toList()
                }
            }.getOrElse { e ->
                suppressed.add(IOException("read $path: ${e.message}", e))
                emptyList()
            }
        }
        if (buffers.isEmpty()) return null

        return m.invoke(null, buffers.toTypedArray(), suppressed) as Array<*>
    }

    // DexPathList element-builder signature drifts across API levels — try the known variants.
    private fun makeElements(
        dexListCls: Class<*>, files: List<File>, optDir: File, suppressed: MutableList<IOException>
    ): Array<*>? {
        runCatching {
            val m = dexListCls.getDeclaredMethod(
                "makePathElements", List::class.java, File::class.java, List::class.java
            ).apply { isAccessible = true }
            return m.invoke(null, files, optDir, suppressed) as Array<*>
        }
        runCatching {
            val m = dexListCls.getDeclaredMethod(
                "makeDexElements", List::class.java, File::class.java, List::class.java, ClassLoader::class.java
            ).apply { isAccessible = true }
            return m.invoke(null, files, optDir, suppressed, this@Dilink5SdkInjector.javaClass.classLoader) as Array<*>
        }
        return null
    }
}
