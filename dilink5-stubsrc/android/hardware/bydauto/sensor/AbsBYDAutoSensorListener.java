package android.hardware.bydauto.sensor;
// DiLink-5 compile-only stub. Real class (abstract) from the OEM SDK at runtime.
// Slope (getSlope/onSlopeValueChanged) confirmed DEAD on-car (real incline test, byd-probe
// 2026-07-25) — single vendor-signed path (CarVendorSensorManager), no alternate source.
// Probed here anyway (compat-report only, not wired into app telemetry) in case another
// vehicle's firmware has a working vendor implementation.
public abstract class AbsBYDAutoSensorListener {
    public AbsBYDAutoSensorListener() {}
    public void onSlopeValueChanged(int slope) {}
    public void onError(int code, String msg) {}
}
