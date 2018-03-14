/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.trade.protocol.tasks.seller_as_taker;

import bisq.core.btc.AddressEntry;
import bisq.core.btc.data.RawTransactionInput;
import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.trade.Contract;
import bisq.core.trade.Trade;
import bisq.core.trade.protocol.TradingPeer;
import bisq.core.trade.protocol.tasks.TradeTask;

import bisq.common.crypto.Hash;
import bisq.common.taskrunner.TaskRunner;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;

import com.google.common.util.concurrent.FutureCallback;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public class SellerAsTakerSignAndPublishDepositTx extends TradeTask {
    @SuppressWarnings({"WeakerAccess", "unused"})
    public SellerAsTakerSignAndPublishDepositTx(TaskRunner taskHandler, Trade trade) {
        super(taskHandler, trade);
    }

    @Override
    protected void run() {
        try {
            runInterceptHook();
            log.debug("\n\n------------------------------------------------------------\n"
                    + "Contract as json\n"
                    + trade.getContractAsJson()
                    + "\n------------------------------------------------------------\n");

            byte[] contractHash = Hash.getSha256Hash(trade.getContractAsJson());
            trade.setContractHash(contractHash);

            List<RawTransactionInput> sellerInputs = checkNotNull(processModel.getRawTransactionInputs(), "sellerInputs must not be null");
            BtcWalletService walletService = processModel.getBtcWalletService();
            String id = processModel.getOffer().getId();

            Optional<AddressEntry> addressEntryOptional = walletService.getAddressEntry(id, AddressEntry.Context.MULTI_SIG);
            checkArgument(addressEntryOptional.isPresent(), "addressEntryOptional must be present");
            AddressEntry sellerMultiSigAddressEntry = addressEntryOptional.get();
            byte[] sellerMultiSigPubKey = processModel.getMyMultiSigPubKey();
            checkArgument(Arrays.equals(sellerMultiSigPubKey,
                    sellerMultiSigAddressEntry.getPubKey()),
                    "sellerMultiSigPubKey from AddressEntry must match the one from the trade data. trade id =" + id);

            Coin sellerInput = Coin.valueOf(sellerInputs.stream().mapToLong(input -> input.value).sum());

            sellerMultiSigAddressEntry.setCoinLockedInMultiSig(sellerInput.subtract(trade.getTxFee().multiply(2)));
            walletService.saveAddressEntryList();

            TradingPeer tradingPeer = processModel.getTradingPeer();

            Transaction depositTx = processModel.getTradeWalletService().takerSignsAndPublishesDepositTx(
                    true,
                    contractHash,
                    processModel.getPreparedDepositTx(),
                    tradingPeer.getRawTransactionInputs(),
                    sellerInputs,
                    tradingPeer.getMultiSigPubKey(),
                    sellerMultiSigPubKey,
                    trade.getArbitratorBtcPubKey(),
                    new FutureCallback<Transaction>() {
                        @Override
                        public void onSuccess(Transaction transaction) {
                            if (!completed) {
                                log.trace("takerSignAndPublishTx succeeded " + transaction);
                                trade.setState(Trade.State.TAKER_PUBLISHED_DEPOSIT_TX);
                                walletService.swapTradeEntryToAvailableEntry(id, AddressEntry.Context.RESERVED_FOR_TRADE);

                                complete();
                            } else {
                                log.warn("We got the onSuccess callback called after the timeout has been triggered a complete().");
                            }
                        }

                        @Override
                        public void onFailure(@NotNull Throwable t) {
                            if (!completed) {
                                failed(t);
                            } else {
                                log.warn("We got the onFailure callback called after the timeout has been triggered a complete().");
                            }
                        }
                    });
            // We set the deposit tx in case we get the onFailure called.
            trade.setDepositTx(depositTx);
        } catch (Throwable t) {
            final Contract contract = trade.getContract();
            if (contract != null)
                contract.printDiff(processModel.getTradingPeer().getContractAsJson());
            failed(t);
        }
    }
}