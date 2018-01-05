package net.corda.node

import co.paralleluniverse.fibers.Suspendable
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.nodeapi.internal.config.User
import net.corda.testing.driver.driver
import org.bouncycastle.util.io.Streams
import org.junit.Test
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.ALICE_NAME
import java.net.ConnectException
import kotlin.test.assertTrue
import kotlin.test.fail
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import java.util.regex.Pattern

class SSHServerTest {

    @Ignore("Test has undeterministic capacity to hang, ignore till fixed")
    @Test()
    fun `ssh server does not start be default`() {
        val user = User("u", "p", setOf())
        // The driver will automatically pick up the annotated flows below
        driver {
            val node = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user))
            node.getOrThrow()

            val session = JSch().getSession("u", "localhost", 2222)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setPassword("p")

            try {
                session.connect()
                fail()
            } catch (e:JSchException) {
                assertTrue(e.cause is ConnectException)
            }
        }
    }

    @Ignore("Test has undeterministic capacity to hang, ignore till fixed")
    @Test
    fun `ssh server starts when configured`() {
        val user = User("u", "p", setOf())
        // The driver will automatically pick up the annotated flows below
        driver {
            val node = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user),
                    customOverrides = mapOf("sshd" to mapOf("port" to 2222)))
            node.getOrThrow()

            val session = JSch().getSession("u", "localhost", 2222)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setPassword("p")

            session.connect()

            assertTrue(session.isConnected)
        }
    }


    @Ignore("Test has undeterministic capacity to hang, ignore till fixed")
    @Test
    fun `ssh server verify credentials`() {
        val user = User("u", "p", setOf())
        // The driver will automatically pick up the annotated flows below
        driver {
            val node = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user),
                    customOverrides = mapOf("sshd" to mapOf("port" to 2222)))
            node.getOrThrow()

            val session = JSch().getSession("u", "localhost", 2222)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setPassword("p_is_bad_password")

            try {
                session.connect()
                fail("Server should reject invalid credentials")
            } catch (e: JSchException) {
                //There is no specialized exception for this
                assertTrue(e.message == "Auth fail")
            }
        }
    }

    @Ignore("Test has undeterministic capacity to hang, ignore till fixed")
    @Test
    fun `ssh respects permissions`() {
        val user = User("u", "p", setOf(startFlow<FlowICanRun>()))
        // The driver will automatically pick up the annotated flows below
        driver(isDebug = true) {
            val node = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user),
                    customOverrides = mapOf("sshd" to mapOf("port" to 2222)))
            node.getOrThrow()

            val session = JSch().getSession("u", "localhost", 2222)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setPassword("p")
            session.connect()

            assertTrue(session.isConnected)

            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("start FlowICannotRun otherParty: \"${ALICE_NAME}\"")
            channel.connect()
            val response = String(Streams.readAll(channel.inputStream))

            val flowNameEscaped = Pattern.quote("StartFlow.${SSHServerTest::class.qualifiedName}$${FlowICannotRun::class.simpleName}")

            channel.disconnect()
            session.disconnect()

            assertThat(response).matches("(?s)User not authorized to perform RPC call .*")
        }
    }

    @Ignore("Test has undeterministic capacity to hang, ignore till fixed")
    @Test
    fun `ssh runs flows`() {
        val user = User("u", "p", setOf(startFlow<FlowICanRun>()))
        // The driver will automatically pick up the annotated flows below
        driver(isDebug = true) {
            val node = startNode(providedName = ALICE_NAME, rpcUsers = listOf(user),
                    customOverrides = mapOf("sshd" to mapOf("port" to 2222)))
            node.getOrThrow()

            val session = JSch().getSession("u", "localhost", 2222)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setPassword("p")
            session.connect()

            assertTrue(session.isConnected)

            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("start FlowICanRun")
            channel.connect()

            val response = String(Streams.readAll(channel.inputStream))

            // There are ANSI control characters involved, so we want to avoid direct byte to byte matching.
            assertThat(response.lines()).filteredOn( { it.contains("Done")}).hasSize(1)
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class FlowICanRun : FlowLogic<String>() {

        private val HELLO_STEP = ProgressTracker.Step("Hello")

        @Suspendable
        override fun call(): String {
            progressTracker?.currentStep = HELLO_STEP
            return "bambam"
        }

        override val progressTracker: ProgressTracker? = ProgressTracker(HELLO_STEP)
    }

    @StartableByRPC
    @InitiatingFlow
    class FlowICannotRun(val otherParty: Party) : FlowLogic<String>() {
        @Suspendable
        override fun call(): String = initiateFlow(otherParty).receive<String>().unwrap { it }

        override val progressTracker: ProgressTracker? = ProgressTracker()
    }
}