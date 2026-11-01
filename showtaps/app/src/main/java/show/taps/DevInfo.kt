package show.taps

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.Keep

/**
 * 输入设备信息。手写 Parcelable —— AGP 9 内置 Kotlin 后不再使用 kotlin-parcelize 插件。
 */
@Keep
data class DevInfo(
    val path: String,
    val name: String?,
    val width: Int,
    val height: Int,
    val type: Int,
    var weight: Int,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(path)
        parcel.writeString(name)
        parcel.writeInt(width)
        parcel.writeInt(height)
        parcel.writeInt(type)
        parcel.writeInt(weight)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<DevInfo> {
        override fun createFromParcel(parcel: Parcel): DevInfo = DevInfo(parcel)
        override fun newArray(size: Int): Array<DevInfo?> = arrayOfNulls(size)
    }
}
