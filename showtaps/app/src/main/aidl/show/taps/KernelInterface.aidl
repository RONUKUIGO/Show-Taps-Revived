package show.taps;

import show.taps.ITouchCallback;

interface KernelInterface {
    /**
     * 开始读取触摸设备。
     * @param dev 优先使用的 /dev/input/eventX， 表示自动挑选
     * @param callback 触摸数据回调（App 进程里的实现）
     */
    boolean start(@nullable String dev, in ITouchCallback callback);

    boolean isRunning();

    void stop();

    oneway void exit();
}
