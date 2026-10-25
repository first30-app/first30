package com.first30.app.incident

import com.first30.core.engine.Judge
import com.first30.core.engine.JudgeInput
import com.first30.core.engine.Judged
import com.first30.core.engine.JevRequest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Hard time limit around a (network) [Judge] — CLAUDE.md item 6: no answer within 3 s → the engine's `run` catches the
 * exception and falls back to the local rule judge (result.judge.fallback = true). Wrap the relay-server JevJudge with it.
 */
class TimeLimitedJudge(
    private val inner: Judge,
    private val timeoutMs: Long = 3000,
    private val executor: ExecutorService = DEFAULT_EXECUTOR,
) : Judge {
    override fun judge(request: JevRequest, input: JudgeInput): Judged? {
        val f = executor.submit(Callable { inner.judge(request, input) })
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            throw IllegalStateException("판정 모델이 ${timeoutMs}ms 안에 답하지 않았습니다")
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    companion object {
        private val DEFAULT_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "first30-judge").apply { isDaemon = true }
        }
    }
}
