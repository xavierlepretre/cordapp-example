package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.IOUContract
import com.example.state.IOUState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.requireThat
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step

/**
 * This flow allows two parties (the [Initiator] and the [Acceptor]) to come to an agreement about the closing of the IOU
 * encapsulated within the input [IOUState].
 *
 * In our simple example, the [Acceptor] always accepts a valid transaction.
 *
 * These flows have deliberately been implemented by using only the call() method for ease of understanding. In
 * practice we would recommend splitting up the various stages of the flow into sub-routines.
 *
 * All methods called within the [FlowLogic] sub-class need to be annotated with the @Suspendable annotation.
 */
object DestroyFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val inputRef: StateRef) : FlowLogic<SignedTransaction>() {

        constructor(txSecurehash: SecureHash, index: Int) : this(StateRef(txSecurehash, index))
        constructor(txhash: String, index: Int) : this(SecureHash.parse(txhash), index)
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object FETCHING_FROM_VAULT : Step("Fetching relevant info from vault.")
            object GENERATING_TRANSACTION : Step("Generating transaction based on new IOU.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    FETCHING_FROM_VAULT,
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            progressTracker.currentStep = FETCHING_FROM_VAULT

            val createTx = serviceHub.validatedTransactions.getTransaction(inputRef.txhash)!!
            val inputStateAndRef = serviceHub.toStateAndRef<IOUState>(inputRef)
            val inputState = inputStateAndRef.state.data

            // Obtain a reference to the notary of the input.
            val notary = createTx.notary!!

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val requiredSigners = setOf(inputState.borrower, inputState.lender)
            val txCommand = Command(
                    IOUContract.Commands.Destroy(), requiredSigners.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addInputState(inputStateAndRef)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartyFlow = initiateFlow(requiredSigners.minus(serviceHub.myInfo.legalIdentities.first()).single())
            val fullySignedTx = subFlow(CollectSignaturesFlow(
                    partSignedTx, setOf(otherPartyFlow), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                }
            }

            return subFlow(signTransactionFlow)
        }
    }
}
