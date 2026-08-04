package android.hardware.bydauto.instrument;

import android.hardware.bydauto.BYDAutoEventValue;

// Compile-only stub. Real class (concrete, ~196 callbacks) provided at runtime by the DiLink-5
// dilink5-sdk.jar; absent on DiLink-3 (harmless — the listener just never registers/fires). We only
// declare the callbacks we actually override; the real class shadows this and carries the rest.
// Used for event-driven drive mode + ambient temperature (D3 parity — no polling).
public class AbsBYDAutoInstrumentListener {
    public AbsBYDAutoInstrumentListener() {}
    public void onSportModeStateChanged(int state) {}      // drive mode (1=Eco/2=Sport/3=Normal/4=Snow)
    public void onOutCarTemperatureChanged(int tempC) {}   // ambient / outside-air temperature °C
    // Generic feature-ID event tap — INSTRUMENT_UNIT_PRESSURE (fid=4208) has no dedicated
    // callback, only reaches the listener through this catch-all. Confirmed on-car: int value
    // 1=Bar, 2=PSI, 3=kPa (matches the dash cluster's currently selected tyre-pressure unit).
    public void onDataEventChanged(int featureId, BYDAutoEventValue value) {}
}
