package ai.atalia.chat.sample

import ai.atalia.chat.Atalia
import ai.atalia.chat.AtaliaOptions
import android.app.Application

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Atalia.configure(
            this,
            BuildConfig.ATALIA_CHANNEL_TOKEN,
            AtaliaOptions(baseUrl = BuildConfig.ATALIA_BASE_URL, debug = BuildConfig.DEBUG),
        )
    }
}
