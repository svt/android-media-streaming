package se.svt.videoplayer

sealed class Result<out T, out E> {
    data class Success<T, E>(val data: T) : Result<T, E>()
    data class Error<T, E>(val exception: E) : Result<T, E>()

    val ok get() = (this as? Success)?.data
    val err get() = (this as? Error)?.exception
}

/**
 * Converts a `Result<T, E>?` to `Result<T?, E>`
 */
fun <T, E> Result<T, E>?.transpose() = when (this) {
    is Result.Error -> Result.Error(exception)
    is Result.Success -> Result.Success(data)
    null -> Result.Success<T?, E>(null)
}

inline fun <T, U, E> Result<T, E>.map(closure: (T) -> U): Result<U, E> = when (this) {
    is Result.Success -> Result.Success(closure(data))
    is Result.Error -> Result.Error(exception)
}

inline fun <T, U, E> Result<T, E>.andThen(closure: (T) -> Result<U, E>): Result<U, E> =
    when (this) {
        is Result.Success -> closure(data)
        is Result.Error -> Result.Error(exception)
    }

inline fun <T, E, F> Result<T, E>.mapErr(closure: (E) -> F) = when (this) {
    is Result.Success -> Result.Success<T, F>(data)
    is Result.Error -> Result.Error<T, F>(closure(exception))
}

// Converts a List of Result to Result of List where the exception is the first in the list
fun <T, E> Iterable<Result<T, E>>.collect(): Result<List<T>, E> = Result.Success(
    map {
        when (it) {
            is Result.Error -> return Result.Error(it.exception)
            is Result.Success -> it.data
        }
    }
)

fun <T, E : Exception> Result<T, E>.orThrow() = when (this) {
    is Result.Success -> this.data
    is Result.Error -> throw this.exception
}

/**
 * Converts a `T?` to a `Result<T, E>` where `E` is given as an argument.
 */
fun <T, E> T?.okOr(error: E) = if (this != null) Result.Success<T, E>(this) else Result.Error(error)

/**
 * Converts a `T?` to a `Result<T, E>` where `E` is provided by the closure.
 */
fun <T, E> T?.okOrElse(errorClosure: () -> E) = if (this != null) Result.Success<T, E>(this) else Result.Error(errorClosure())
