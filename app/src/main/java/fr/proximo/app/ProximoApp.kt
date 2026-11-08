package fr.proximo.app

import android.app.Application
import fr.proximo.app.mesh.MeshService

class ProximoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MeshService.createChannels(this)
    }
}
