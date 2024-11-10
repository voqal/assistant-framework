package dev.voqal.core

interface Disposer {
    companion object {
        fun register(parent: Disposable, child: Disposable) {
        }

        fun register(parent: Disposable, exec: () -> Unit) {
        }

        fun newDisposable(): Disposable {
            throw UnsupportedOperationException()
        }

        fun dispose(disposable: Disposable) {
            TODO("Not yet implemented")
        }
    }
}