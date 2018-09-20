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
package com.sliva.btc.scanner;

import com.sliva.btc.scanner.db.DBConnection;
import com.sliva.btc.scanner.db.DbAddWallet;
import com.sliva.btc.scanner.db.DbQueryInput;
import com.sliva.btc.scanner.db.DbQueryTransaction;
import com.sliva.btc.scanner.db.DbQueryWallet;
import com.sliva.btc.scanner.db.DbUpdateAddress;
import com.sliva.btc.scanner.db.model.BtcAddress;
import com.sliva.btc.scanner.db.model.BtcTransaction;
import com.sliva.btc.scanner.src.SrcAddressType;
import com.sliva.btc.scanner.util.Utils;
import java.io.File;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

/**
 *
 * @author whost
 */
@Slf4j
public class RunUpdateWallets2 {

    private static final int DEFAULT_TXN_THREADS = 20;
    private static final int DEFAULT_FIRST_TRANSACTION = 2_200_000;
    private static final int DEFAULT_BATCH_SIZE = 10000;
    private static final String DEFAULT_STOP_FILE_NAME = "/tmp/btc-update-wallet-stop";
    private static final String SQL_UPDATE_ADDRESS_WALLET
            = "UPDATE address_table_name SET wallet_id=? WHERE wallet_id=?";

    private final File stopFile;
    private final DBConnection conn;
    private final DbQueryTransaction queryTransaction;
    private final DbQueryInput queryInput;
    private final DbQueryWallet queryWallet;
    private final Collection<ThreadLocal<PreparedStatement>> psUpdateAddressWalletPerTable;
    private final int firstTransaction;
    private final int batchSize;
    private final int txnThreads;
    private final Set<Integer> unusedWallets = new HashSet<>();
    private final ExecutorService execAddressQueries = Executors.newFixedThreadPool(BtcAddress.getRealTypes().size());
    private final ExecutorService execTransactionThreads;
    private final Utils.NumberFile startFromFile;

    public static void main(String[] args) throws Exception {
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(prepOptions(), args);
        if (cmd.hasOption('h')) {
            printHelpAndExit();
        }
        new RunUpdateWallets2(cmd).runProcess();
    }

    public RunUpdateWallets2(CommandLine cmd) throws SQLException {
        stopFile = new File(cmd.getOptionValue("stop-file", DEFAULT_STOP_FILE_NAME));
        startFromFile = new Utils.NumberFile(cmd.getOptionValue("start-from", Integer.toString(DEFAULT_FIRST_TRANSACTION)));
        firstTransaction = startFromFile.getNumber().intValue();
        batchSize = Integer.parseInt(cmd.getOptionValue("batch-size", Integer.toString(DEFAULT_BATCH_SIZE)));
        txnThreads = Integer.parseInt(cmd.getOptionValue("threads", Integer.toString(DEFAULT_TXN_THREADS)));
        DBConnection.applyArguments(cmd);

        conn = new DBConnection();
        psUpdateAddressWalletPerTable = BtcAddress.getRealTypes().stream().map(type -> conn.prepareStatement(fixAddressTableName(SQL_UPDATE_ADDRESS_WALLET, type))).collect(Collectors.toList());
        queryTransaction = new DbQueryTransaction(conn);
        queryInput = new DbQueryInput(conn);
        queryWallet = new DbQueryWallet(conn);
        execTransactionThreads = Executors.newFixedThreadPool(txnThreads);
    }

    private void runProcess() throws SQLException, InterruptedException {
        log.info("START");
        try {
            try (DbUpdateAddress updateAddress = new DbUpdateAddress(conn);
                    DbAddWallet addWallet = new DbAddWallet(conn)) {
                log.debug("Checking for missing wallet records...");
                queryWallet.getMissingWalletRecords().stream().forEach(w -> addWallet.add(w));
                addWallet.flushCache();
                log.debug("Checking for unused wallet records...");
                unusedWallets.addAll(queryWallet.getUnusedWalletRecords());
                if (!unusedWallets.isEmpty()) {
                    log.warn("Found {} unused wallet records: {}", unusedWallets.size(), unusedWallets);
                }
                int batchFirstTransaction = firstTransaction;
                for (int loop = 0;; loop++, batchFirstTransaction += batchSize) {
                    startFromFile.updateNumber(batchFirstTransaction);
                    if (stopFile.exists()) {
                        log.info("Exiting - stop file found: " + stopFile.getAbsolutePath());
                        stopFile.renameTo(new File(stopFile.getAbsoluteFile() + "1"));
                        break;
                    }
                    int batchLastTransaction = batchFirstTransaction + batchSize - 1;
                    log.debug("Batch loop #{}. Process transactions [{} - {}]", loop, batchFirstTransaction, batchLastTransaction);
                    processBatch(batchFirstTransaction, batchLastTransaction, updateAddress, addWallet);
                    addWallet.flushCache();
                }
            }
        } finally {
            execAddressQueries.shutdown();
            execTransactionThreads.shutdown();
            log.info("FINISH");
        }
    }

    private void processBatch(int firstTransaction, int lastTransaction, DbUpdateAddress updateAddress, DbAddWallet addWallet) throws SQLException, InterruptedException {
        AtomicInteger newWalletsAssigned = new AtomicInteger();
        AtomicInteger walletsMerged = new AtomicInteger();
        final Collection<BtcTransaction> needToProcess = new ArrayList<>();
        execTransactionThreads.invokeAll(queryTransaction.getTxnsRangle(firstTransaction, lastTransaction).stream().filter(tx -> tx.getNInputs() != 0).map(tx -> (Callable<Object>) () -> {
            List<BtcAddress> addresses = queryInput.getInputAddresses(tx.getTransactionId());
            if (addresses == null || addresses.isEmpty()) {
                log.warn("Unexpected: Addresses list is empty for transactionId " + tx.getTransactionId());
            } else {
                List<Integer> wallets = addresses.stream().map((a) -> a.getWalletId()).distinct().sorted().collect(Collectors.toList());
                if (wallets.isEmpty()) {
                    log.warn("Unexpected: Wallets list is empty for transactionId " + tx.getTransactionId());
                } else if (wallets.get(0) == 0 || wallets.size() > 1) {
                    synchronized (needToProcess) {
                        needToProcess.add(tx);
                    }
                }
            }
            return null;
        }).collect(Collectors.toList()));
        for (BtcTransaction tx : needToProcess) {
            List<BtcAddress> addresses = queryInput.getInputAddresses(tx.getTransactionId());
            List<Integer> wallets = addresses.stream().map((a) -> a.getWalletId()).distinct().sorted().collect(Collectors.toList());
            if (!wallets.isEmpty() && wallets.get(0) == 0) {
                //wallet is not assigned yet
                int newWalletId = wallets.size() > 1 ? wallets.get(1) : getNextWalletId(addWallet);
                addresses.stream().filter(a -> a.getWalletId() == 0).forEach(a -> updateAddress.updateWallet(a.getAddressId(), newWalletId));
                updateAddress.flushCache();
                wallets.remove(0);
                newWalletsAssigned.incrementAndGet();
            }
            if (wallets.size() > 1) {
                //merge multiple wallets
                int walletToUse = wallets.get(0);
                wallets.stream().filter(w -> w != walletToUse).forEach(w -> replaceWallet(walletToUse, w));
                walletsMerged.addAndGet(wallets.size() - 1);
            }
        }
        if (newWalletsAssigned.get() != 0 || walletsMerged.get() != 0) {
            log.info("newWalletsAssigned: {}, walletsMerged={}", newWalletsAssigned, walletsMerged);
        }
    }

    private int replaceWallet(int walletToUse, int walletToReplace) {
        if (walletToUse == 0 || walletToReplace == 0 || walletToUse == walletToReplace) {
            throw new IllegalArgumentException("walletToUse=" + walletToUse + ", walletToReplace=" + walletToReplace);
        }
        try {
            final AtomicInteger nUpdated = new AtomicInteger();
            execAddressQueries.invokeAll(psUpdateAddressWalletPerTable.stream().map(ps -> (Callable<Object>) () -> {
                try {
                    ps.get().setInt(1, walletToUse);
                    ps.get().setInt(2, walletToReplace);
                    nUpdated.addAndGet(ps.get().executeUpdate());
                    return null;
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList()));
//            log.debug("Merging wallets: ({},{})=>{}. Records updated: {}", walletToUse, walletToReplace, walletToUse, nUpdated);
            unusedWallets.add(walletToReplace);
            return nUpdated.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private int getNextWalletId(DbAddWallet addWallet) throws SQLException {
        synchronized (unusedWallets) {
            if (!unusedWallets.isEmpty()) {
                Integer w = unusedWallets.iterator().next();
                unusedWallets.remove(w);
                return w;
            }
        }
        return addWallet.add(null).getWalletId();
    }

    private String fixAddressTableName(String sql, SrcAddressType addressType) {
        return sql.replaceAll("address_table_name", "address_" + addressType.name().toLowerCase());
    }

    private static void printHelpAndExit() {
        System.out.println("Available options:");
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("java <jar> " + Main.Command.update_wallets + " [options]", prepOptions());
        System.exit(1);
    }

    private static Options prepOptions() {
        Options options = new Options();
        options.addOption("h", "help", false, "Print help");
        options.addOption(null, "start-from", true, "First transaction Id to process. Beside a number this parameter can be set to a file name thats stores the numeric value updated on every batch");
        options.addOption(null, "batch-size", true, "Number or transactions to read in a batch. Default: " + DEFAULT_BATCH_SIZE);
        options.addOption(null, "stop-file", true, "File to be watched on each new block to stop process. If file is present the process stops and file renamed by adding '1' to the end. Default: " + DEFAULT_STOP_FILE_NAME);
        options.addOption("t", "threads", true, "Number of threads to run. Default is " + DEFAULT_TXN_THREADS);
        DBConnection.addOptions(options);
        return options;
    }
}