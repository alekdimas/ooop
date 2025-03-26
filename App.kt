package com.example.individualproxy.model




import android.app.Application
import com.example.individualproxy.model.data.api.AuthApi
import com.example.individualproxy.model.data.session.SessionManager
import com.example.individualproxy.model.data.api.UpdateApi
import com.example.individualproxy.model.data.api.VpnApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MyApp : Application() {

    lateinit var authApi: AuthApi
    lateinit var updateApi: UpdateApi
    lateinit var vpnApi: VpnApi
    lateinit var sessionManager: SessionManager

    override fun onCreate() {
        super.onCreate()

        sessionManager = SessionManager(this)

        val okHttpClient = OkHttpClient.Builder()
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("http://158.160.173.144:8000/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        authApi = retrofit.create(AuthApi::class.java)
        updateApi = retrofit.create(UpdateApi::class.java)
        vpnApi = retrofit.create(VpnApi::class.java)
    }
}
