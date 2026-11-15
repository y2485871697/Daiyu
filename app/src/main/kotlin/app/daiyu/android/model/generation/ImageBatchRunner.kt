package app.daiyu.android.model.generation

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException

/** Local bounded scheduling only; results keep request order and failed jobs are never retried. */
internal object ImageBatchRunner {
    fun <T> run(count: Int, concurrency: Int, checkCancelled: () -> Unit, work: (Int) -> T): List<Result<T>> {
        require(count in 1..10 && concurrency in 1..8)
        val pool = Executors.newFixedThreadPool(minOf(count,concurrency))
        val futures = mutableListOf<java.util.concurrent.Future<Result<T>>>()
        try {
            checkCancelled()
            repeat(count) { index ->
                futures += pool.submit(Callable {
                    checkCancelled()
                    try { Result.success(work(index)) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { checkCancelled(); Result.failure(failure) }
                })
            }
            return futures.map { future ->
                checkCancelled()
                try { future.get().also { checkCancelled() } }
                catch (failure: ExecutionException) { throw failure.cause ?: failure }
            }
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
            pool.shutdownNow()
        }
    }
}
