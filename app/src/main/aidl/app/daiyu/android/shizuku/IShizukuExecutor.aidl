package app.daiyu.android.shizuku;
import android.os.Bundle;
interface IShizukuExecutor {
    Bundle attach(IBinder owner, String permissionGeneration) = 0;
    Bundle execute(String serviceGeneration, String permissionGeneration, int expectedUid,
        String requestId, in byte[] script, long timeoutMillis) = 1;
    Bundle poll(String serviceGeneration, String permissionGeneration, String requestId, int offset, int limit) = 2;
    Bundle cancel(String serviceGeneration, String permissionGeneration, String requestId) = 3;
    Bundle closeExecution(String serviceGeneration, String permissionGeneration) = 4;
    void destroy() = 16777114;
}
