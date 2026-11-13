package com.lttbdd.socialagent

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.lttbdd.socialagent.data.network.LinkedInBffAuth
import com.lttbdd.socialagent.databinding.ActivityMainBinding
import com.lttbdd.socialagent.feature.channel.LinkedInAuthRedirect
import com.lttbdd.socialagent.feature.channel.LinkedInCallbackBus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Activity duy nhất của app: chứa NavHostFragment và bottom navigation 5 tab.
 *
 * Deep link LinkedIn: BFF chuyển trình duyệt về `com.lttbdd.socialagent://oauth/linkedin/callback?...`. Activity
 * exported này KHÔNG tự kết nối kênh từ intent bên ngoài; nó chỉ chuyển URL đúng dạng sang [LinkedInCallbackBus],
 * còn ViewModel mới quyết định (chỉ khi đang chờ đăng nhập, `state` khớp, có `code_verifier` trong RAM).
 * Facebook đăng nhập qua Meta SDK nên không đi qua đây.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var linkedInCallbackBus: LinkedInCallbackBus

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHost.navController)

        // Mở lại Activity do deep link (khi app đang chạy nền nhưng Activity bị hủy): chỉ xử lý lần khởi tạo đầu.
        if (savedInstanceState == null) forwardLinkedInCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardLinkedInCallback(intent)
    }

    private fun forwardLinkedInCallback(intent: Intent?) {
        val url = intent?.dataString ?: return
        if (LinkedInAuthRedirect.isRedirect(url, LinkedInBffAuth.APP_REDIRECT_URI)) {
            linkedInCallbackBus.publish(url)
        }
    }
}
