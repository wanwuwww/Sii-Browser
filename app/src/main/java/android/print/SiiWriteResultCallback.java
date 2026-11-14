package android.print;

/**
 * Android 36 的 SDK stub 把 WriteResultCallback 构造函数标成了包内可见，
 * 应用侧无法直接 new。这里放在同一包内公开一个构造函数，供业务代码继承。
 */
public class SiiWriteResultCallback extends PrintDocumentAdapter.WriteResultCallback {
    public SiiWriteResultCallback() {
        super();
    }
}
