package show.taps;

/**
 * 触摸数据回调：提权进程（Shizuku 用户服务 / root 守护进程）读完 /dev/input 后回传给 App 进程。
 * 全部 oneway，避免阻塞读取线程。
 */
oneway interface ITouchCallback {
    void onSlotState(int slot, boolean down);
    void onSlotMove(int slot, float x, float y);
    void onSync();
}
