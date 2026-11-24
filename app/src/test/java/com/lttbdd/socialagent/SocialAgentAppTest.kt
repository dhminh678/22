package com.lttbdd.socialagent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.repository.DataStoreChannelAccountStore
import com.lttbdd.socialagent.data.repository.StoredChannelAccount
import com.lttbdd.socialagent.data.security.AndroidKeystoreCipher
import com.lttbdd.socialagent.data.security.PendingChannelWrite
import com.lttbdd.socialagent.data.security.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import java.security.Provider
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(application = SocialAgentApp::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
@OptIn(ExperimentalCoroutinesApi::class)
class SocialAgentAppTest {

    @Test
    fun startupResumesPendingDisconnectWithoutOpeningChannelScreen() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val provider = installTestKeystore()
        try {
            // Giữ coroutine khởi động lại cho đến khi đã dựng trạng thái trên đĩa của lần chạy trước.
            val app = ApplicationProvider.getApplicationContext<SocialAgentApp>()
            val accountStore = DataStoreChannelAccountStore(app)
            val tokenStore = SecureTokenStore(app, AndroidKeystoreCipher())
            val write = PendingChannelWrite(
                id = "interrupted",
                type = ChannelType.LINKEDIN,
                accountId = "member",
                disconnect = true,
                clearChannelData = true,
            )
            val database = Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.NAME).build()
            try {
                runBlocking {
                    accountStore.upsert(StoredChannelAccount(write.type, write.accountId, "LinkedIn"))
                    tokenStore.stageDisconnect(write)
                    database.draftDao().upsert(
                        DraftEntity("draft", "topic", write.type, "Draft", null, null, false, 0),
                    )
                }

                runTest(dispatcher) {
                    val cleaned = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(5_000) {
                            while (tokenStore.loadPendingWrites().isNotEmpty()) delay(25)
                            true
                        } ?: false
                    }
                    assertTrue("App startup did not finish the pending disconnect", cleaned)
                    assertTrue(accountStore.load().isEmpty())
                    assertNull(database.draftDao().getById("draft"))
                    assertEquals(write.id, database.channelCleanupDao().getCompletedOperation(write.type))
                }
            } finally {
                database.close()
            }
        } finally {
            Security.removeProvider(provider.name)
            Dispatchers.resetMain()
        }
    }

    private fun installTestKeystore(): Provider {
        // Robolectric không có Android Keystore. Kịch bản này chỉ xoá token, không dùng khoá mã hoá.
        val jdkKeystore = Security.getProvider("SUN").getService("KeyStore", "PKCS12")
        val provider = object : Provider("SocialAgentTestKeystore", 1.0, "Test keystore") {
            init {
                putService(object : Service(this, "KeyStore", "AndroidKeyStore", jdkKeystore.className, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = jdkKeystore.newInstance(null)
                })
            }
        }
        Security.addProvider(provider)
        return provider
    }
}
