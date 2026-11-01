package show.taps.server

import android.util.Log
import show.taps.DevInfo

private const val TAG = "InputDevices"

object InputDevices {

    /** 扫描 /dev/input 下所有 B 协议多点触控设备 */
    fun list(): List<DevInfo> = runCatching { NativeLib.getInputPath().toList() }
        .onFailure { Log.e(TAG, "scan failed", it) }
        .getOrDefault(emptyList())

    /**
     * 挑选触摸设备：优先用户上次触摸的设备名（+100000 权重），否则取权重最高的。
     * 返回 /dev/input/eventX 路径。
     */
    fun pick(preferredName: String?): String? {
        val all = list()
        if (all.isEmpty()) {
            Log.e(TAG, "pick: no input device found")
            return null
        }
        if (preferredName != null) {
            all.forEach { if (it.name == preferredName) it.weight += 100_000 }
        }
        var best = all[0]
        for (d in all) if (d.weight > best.weight) best = d
        Log.d(TAG, "pick: ${best.path} (${best.name}) weight=${best.weight}")
        return best.path
    }
}
