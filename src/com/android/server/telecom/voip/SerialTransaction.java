/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.voip;

import java.util.List;

/**
 * A VoipCallTransaction implementation that its sub transactions will be executed in serial
 */
public class SerialTransaction extends VoipCallTransaction {
    public SerialTransaction(List<VoipCallTransaction> subTransactions) {
        super(subTransactions);
    }

    public void appendTransaction(VoipCallTransaction transaction){
        mSubTransactions.add(transaction);
    }

    @Override
    public void start() {
        // post timeout work
        mHandler.postDelayed(() -> {
            if (mCompleted.getAndSet(true)) {
                return;
            }
            if (mCompleteListener != null) {
                mCompleteListener.onTransactionTimeout(mTransactionName);
            }
            finish();
        }, TIMEOUT_LIMIT);

        if (mSubTransactions != null && mSubTransactions.size() > 0) {
            TransactionManager.TransactionCompleteListener subTransactionListener =
                    new TransactionManager.TransactionCompleteListener() {

                        @Override
                        public void onTransactionCompleted(VoipCallTransactionResult result,
                                String transactionName) {
                            if (result.getResult() != VoipCallTransactionResult.RESULT_SUCCEED) {
                                mHandler.post(() -> {
                                    VoipCallTransactionResult mainResult =
                                            new VoipCallTransactionResult(
                                                    VoipCallTransactionResult.RESULT_FAILED,
                                                    String.format("sub transaction %s failed",
                                                            transactionName));
                                    mCompleteListener.onTransactionCompleted(mainResult,
                                            mTransactionName);
                                    finish();
                                });
                            } else {
                                if (mSubTransactions.size() > 0) {
                                    VoipCallTransaction transaction = mSubTransactions.remove(0);
                                    transaction.setCompleteListener(this);
                                    transaction.start();
                                } else {
                                    scheduleTransaction();
                                }
                            }
                        }

                        @Override
                        public void onTransactionTimeout(String transactionName) {
                            mHandler.post(() -> {
                                VoipCallTransactionResult mainResult = new VoipCallTransactionResult(
                                        VoipCallTransactionResult.RESULT_FAILED,
                                        String.format("sub transaction %s timed out",
                                                transactionName));
                                mCompleteListener.onTransactionCompleted(mainResult,
                                        mTransactionName);
                                finish();
                            });
                        }
                    };
            VoipCallTransaction transaction = mSubTransactions.remove(0);
            transaction.setCompleteListener(subTransactionListener);
            transaction.start();
        } else {
            scheduleTransaction();
        }
    }
}
