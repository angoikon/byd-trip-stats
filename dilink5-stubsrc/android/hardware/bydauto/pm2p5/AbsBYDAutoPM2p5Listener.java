package android.hardware.bydauto.pm2p5;
// DiLink-5 compile-only stub. Real class (abstract) from the OEM SDK at runtime.
// Confirmed DEAD on-car (AC-toggle test, byd-probe 2026-07-25) — blocked at vendor
// HvacAdapterManager, never dispatches. Probed here anyway (compat-report only, not
// wired into app telemetry) in case another vehicle's firmware wires it up.
public abstract class AbsBYDAutoPM2p5Listener {
    public AbsBYDAutoPM2p5Listener() {}
    public void onPM2p5ValueChanged(int inCar, int outCar) {}
    public void onPM2p5LevelChanged(int inCar, int outCar) {}
    public void onPM2p5OnlineStateChanged(int state) {}
    public void onError(int code, String msg) {}
}
