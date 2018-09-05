package net.corda.finance.obligation

import net.corda.client.jackson.JacksonSupport
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.internal.checkOkResponse
import net.corda.core.internal.openHttpConnection
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import java.io.OutputStreamWriter
import java.net.URI

inline fun <reified T : LinearState> getLinearStateById(
        linearId: UniqueIdentifier,
        services: ServiceHub
): StateAndRef<T>? {
    val query = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
    return services.vaultService.queryBy<T>(query).states.singleOrNull()
}

fun jsonRpcRequest(uri: URI, request: String): String {
    return uri.toURL().openHttpConnection().run {
        doInput = true
        doOutput = true
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(outputStream).use { out -> out.write(request) }
        checkOkResponse()
        inputStream.reader().readText()
    }
}

val mapper = JacksonSupport.createNonRpcMapper()