/* 
 * Copyright 2018 Sliva Co.
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
package com.sliva.btc.scanner.db;

import com.sliva.btc.scanner.db.model.BtcAddress;
import com.sliva.btc.scanner.db.model.TxInput;
import com.sliva.btc.scanner.db.model.TxOutput;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Sliva Co
 */
@Slf4j
public class DbQueryInput {

    private static final String SQL_QUERY_INPUTS = "SELECT pos,in_transaction_id,in_pos FROM input WHERE transaction_id=? ORDER BY pos";
    private static final String SQL_FIND_INPUT_BY_OUT_TX = "SELECT transaction_id,pos FROM input WHERE in_transaction_id=? AND in_pos=? LIMIT 1";
    private static final String SQL_QUERY_INPUTS_WITH_OUTPUT = "SELECT"
            + " I.pos,I.in_transaction_id,I.in_pos"
            + ",O.address_id,O.amount,O.spent"
            + " FROM input I"
            + " INNER JOIN output O ON O.transaction_id=I.in_transaction_id AND O.pos = I.in_pos"
            + " WHERE I.transaction_id=? ORDER BY I.pos";
    private static final String SQL_QUERY_INPUT_ADDRESSES
            = "SELECT O.address_id,IFNULL(P2PKH.wallet_id, IFNULL(P2SH.wallet_id, IFNULL(P2WPKH.wallet_id, P2WSH.wallet_id)))"
            + " FROM input I"
            + " INNER JOIN output O ON O.transaction_id=I.in_transaction_id AND O.pos=I.in_pos"
            + " LEFT JOIN address_p2pkh P2PKH ON P2PKH.address_id=O.address_id"
            + " LEFT JOIN address_p2sh P2SH ON P2SH.address_id=O.address_id"
            + " LEFT JOIN address_p2wpkh P2WPKH ON P2WPKH.address_id=O.address_id"
            + " LEFT JOIN address_p2wsh P2WSH ON P2WSH.address_id=O.address_id"
            + " WHERE O.address_id>0 AND I.transaction_id=?";
    private final ThreadLocal<PreparedStatement> psQueryInputs;
    private final ThreadLocal<PreparedStatement> psFindInputByOutTx;
    private final ThreadLocal<PreparedStatement> psQueryInputsWithOutput;
    private final ThreadLocal<PreparedStatement> psQueryInputAddresses;

    public DbQueryInput(DBConnection conn) {
        this.psQueryInputs = conn.prepareStatement(SQL_QUERY_INPUTS);
        this.psFindInputByOutTx = conn.prepareStatement(SQL_FIND_INPUT_BY_OUT_TX);
        this.psQueryInputsWithOutput = conn.prepareStatement(SQL_QUERY_INPUTS_WITH_OUTPUT);
        this.psQueryInputAddresses = conn.prepareStatement(SQL_QUERY_INPUT_ADDRESSES);
    }

    public List<TxInput> getInputs(int transactionId) throws SQLException {
        psQueryInputs.get().setInt(1, transactionId);
        List<TxInput> result = new ArrayList<>();
        try (ResultSet rs = psQueryInputs.get().executeQuery()) {
            while (rs.next()) {
                result.add(TxInput.builder()
                        .transactionId(transactionId)
                        .pos(rs.getShort(1))
                        .inTransactionId(rs.getInt(2))
                        .inPos(rs.getShort(3))
                        .build());
            }
        }
        log.trace("getInputs(transactionId:{}): result={}", transactionId, result);
        return result;
    }

    public TxInput findInputByOutTx(int inTransactionId, short inPos) throws SQLException {
        psFindInputByOutTx.get().setInt(1, inTransactionId);
        psFindInputByOutTx.get().setInt(2, inPos);
        try (ResultSet rs = psFindInputByOutTx.get().executeQuery()) {
            TxInput result = rs.next() ? TxInput.builder()
                    .transactionId(rs.getInt(1))
                    .pos(rs.getShort(2))
                    .inTransactionId(inTransactionId)
                    .inPos(inPos)
                    .build() : null;
            log.trace("findInputByOutTx(inTransactionId:{},inPos:{}): result={}", inTransactionId, inPos, result);
            return result;
        }
    }

    public List<TxInputOutput> getInputsWithOutput(int transactionId) throws SQLException {
        psQueryInputsWithOutput.get().setInt(1, transactionId);
        try (ResultSet rs = psQueryInputsWithOutput.get().executeQuery()) {
            List<TxInputOutput> result = new ArrayList<>();
            while (rs.next()) {
                TxInputOutput.TxInputOutputBuilder builder = TxInputOutput.builder();
                builder.input(TxInput.builder()
                        .transactionId(transactionId)
                        .pos(rs.getShort(1))
                        .inTransactionId(rs.getInt(2))
                        .inPos(rs.getShort(3))
                        .build());
                if (rs.getObject(4) != null) {
                    builder.output(TxOutput.builder()
                            .transactionId(rs.getInt(2))
                            .pos(rs.getShort(3))
                            .addressId(rs.getInt(4))
                            .amount(rs.getLong(5))
                            .status(rs.getByte(6))
                            .build());
                }
                result.add(builder.build());
            }
            return result;
        }
    }

    public Collection<BtcAddress> getInputAddresses(int transactionId) throws SQLException {
        psQueryInputAddresses.get().setInt(1, transactionId);
        try (ResultSet rs = psQueryInputAddresses.get().executeQuery()) {
            Collection<BtcAddress> result = new HashSet<>();
            while (rs.next()) {
                result.add(BtcAddress.builder()
                        .addressId(rs.getInt(1))
                        .walletId(rs.getInt(2))
                        .build());
            }
            return result;
        }
    }

    @Getter
    @Builder
    @ToString
    public static class TxInputOutput {

        private final TxInput input;
        private final TxOutput output;
    }
}
