package android.hardware.bydauto.ac;
// DiLink-5 compile-only stub. Real class (abstract) from the OEM SDK at runtime.
// Only the member needed for the battery-temp compat probe is declared (the real class
// has ~70 callbacks). All 3 battery-temp candidates (charging.getChargeBatteryTemp(),
// ota.getBatteryTemp(1), ac.getAcSubBatteryTemperature()) confirmed DEAD on-car: flat
// through a full 18%→36% DC fast-charge session — a real HV pack
// would show a measurable rise under that load. Probed here anyway (compat-report only,
// not wired into app telemetry) in case another vehicle's firmware has a working sensor.
public abstract class AbsBYDAutoAcListener {
    public AbsBYDAutoAcListener() {}
    public void onOtaSubBatteryTemperatureChanged(int temp) {}
    public void onError(int code, String msg) {}
}
