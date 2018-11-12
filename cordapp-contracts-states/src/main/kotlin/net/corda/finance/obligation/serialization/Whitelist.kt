package net.corda.finance.obligation.serialization

import net.corda.core.contracts.TokenizableAssetInfo
import net.corda.core.serialization.SerializationWhitelist

class Whitelist : SerializationWhitelist {
    override val whitelist = listOf(TokenizableAssetInfo::class.java)
}