package android.hardware.bydauto.setting;
import android.content.Context;
// DiLink-5 compile-only stub. Real class from the OEM SDK at runtime (Dilink5SdkInjector).
// Only getEnergyFeedback() is needed here — the regen High/Standard mode select. Confirmed
// on-car: setting.getEnergyFeedback() is the REAL regen mode getter
// (2=Standard, 3=High); the parallel energy.getEnergyFeedback() (CarBodyManager path) is dead
// (constant 0 through a parked toggle test).
public class BYDAutoSettingDevice {
    public static BYDAutoSettingDevice getInstance(Context context) { return null; }
    public void registerListener(AbsBYDAutoSettingListener l) {}
    public void unregisterListener(AbsBYDAutoSettingListener l) {}
    public int getEnergyFeedback() { return 0; }
}
