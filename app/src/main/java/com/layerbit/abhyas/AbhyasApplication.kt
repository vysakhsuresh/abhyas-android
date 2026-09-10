package com.layerbit.abhyas

import android.app.Application
import com.layerbit.abhyas.data.repo.AbhyasRepository

class AbhyasApplication : Application() {
    /**
     * One repository for the process. Screens reach it through their ViewModel rather than
     * holding a reference, so nothing in the UI ever touches Room directly.
     */
    val repository: AbhyasRepository by lazy { AbhyasRepository(this) }
}
