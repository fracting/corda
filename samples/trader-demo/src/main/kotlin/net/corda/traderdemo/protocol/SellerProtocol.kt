package net.corda.traderdemo.protocol

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.CommercialPaper
import net.corda.contracts.asset.DUMMY_CASH_ISSUER
import net.corda.core.contracts.*
import net.corda.core.crypto.*
import net.corda.core.days
import net.corda.core.node.NodeInfo
import net.corda.core.protocols.ProtocolLogic
import net.corda.core.seconds
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.protocols.NotaryProtocol
import net.corda.protocols.TwoPartyTradeProtocol
import java.time.Instant
import java.util.*

class SellerProtocol(val otherParty: Party,
                     val amount: Amount<Currency>,
                     override val progressTracker: ProgressTracker = tracker()) : ProtocolLogic<SignedTransaction>() {
    companion object {
        val PROSPECTUS_HASH = SecureHash.parse("decd098666b9657314870e192ced0c3519c2c9d395507a238338f8d003929de9")

        object SELF_ISSUING : ProgressTracker.Step("Got session ID back, issuing and timestamping some commercial paper")

        object TRADING : ProgressTracker.Step("Starting the trade protocol") {
            override fun childProgressTracker(): ProgressTracker = TwoPartyTradeProtocol.Seller.tracker()
        }

        // We vend a progress tracker that already knows there's going to be a TwoPartyTradingProtocol involved at some
        // point: by setting up the tracker in advance, the user can see what's coming in more detail, instead of being
        // surprised when it appears as a new set of tasks below the current one.
        fun tracker() = ProgressTracker(SELF_ISSUING, TRADING)
    }

    @Suspendable
    override fun call(): SignedTransaction {
        progressTracker.currentStep = SELF_ISSUING

        val notary: NodeInfo = serviceHub.networkMapCache.notaryNodes[0]
        val cpOwnerKey = serviceHub.legalIdentityKey
        val commercialPaper = selfIssueSomeCommercialPaper(cpOwnerKey.public.composite, notary)

        progressTracker.currentStep = TRADING

        // Send the offered amount.
        send(otherParty, amount)
        val seller = TwoPartyTradeProtocol.Seller(
                otherParty,
                notary,
                commercialPaper,
                amount,
                cpOwnerKey,
                progressTracker.getChildProgressTracker(TRADING)!!)
        val tradeTX: SignedTransaction = subProtocol(seller, shareParentSessions = true)
        serviceHub.recordTransactions(listOf(tradeTX))

        return tradeTX
    }

    @Suspendable
    fun selfIssueSomeCommercialPaper(ownedBy: CompositeKey, notaryNode: NodeInfo): StateAndRef<CommercialPaper.State> {
        // Make a fake company that's issued its own paper.
        val keyPair = generateKeyPair()
        val party = Party("Bank of London", keyPair.public)

        val issuance: SignedTransaction = run {
            val tx = CommercialPaper().generateIssue(party.ref(1, 2, 3), 1100.DOLLARS `issued by` DUMMY_CASH_ISSUER,
                    Instant.now() + 10.days, notaryNode.notaryIdentity)

            // TODO: Consider moving these two steps below into generateIssue.

            // Attach the prospectus.
            tx.addAttachment(serviceHub.storageService.attachments.openAttachment(PROSPECTUS_HASH)!!.id)

            // Requesting timestamping, all CP must be timestamped.
            tx.setTime(Instant.now(), 30.seconds)

            // Sign it as ourselves.
            tx.signWith(keyPair)

            // Get the notary to sign the timestamp
            val notarySig = subProtocol(NotaryProtocol.Client(tx.toSignedTransaction(false)))
            tx.addSignatureUnchecked(notarySig)

            // Commit it to local storage.
            val stx = tx.toSignedTransaction(true)
            serviceHub.recordTransactions(listOf(stx))

            stx
        }

        // Now make a dummy transaction that moves it to a new key, just to show that resolving dependencies works.
        val move: SignedTransaction = run {
            val builder = TransactionType.General.Builder(notaryNode.notaryIdentity)
            CommercialPaper().generateMove(builder, issuance.tx.outRef(0), ownedBy)
            builder.signWith(keyPair)
            val notarySignature = subProtocol(NotaryProtocol.Client(builder.toSignedTransaction(false)))
            builder.addSignatureUnchecked(notarySignature)
            val tx = builder.toSignedTransaction(true)
            serviceHub.recordTransactions(listOf(tx))
            tx
        }

        return move.tx.outRef(0)
    }

}