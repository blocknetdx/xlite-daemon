package io.cloudchains.app.net.api.http.client;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.App;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.net.CoinTickerUtils;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.UTXO;
import io.cloudchains.app.util.history.Transaction;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.bitcoinj.core.Address;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class HTTPClient {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);

    private CloseableHttpClient client;
    private ConcurrentHashMap<String, Long> lastFetchTimes;
    private int logCount = 0;

    public HTTPClient(int maximumSockets) {
        SSLContext sslContext = null;
        lastFetchTimes = new ConcurrentHashMap<>();

        try {
            sslContext = new SSLContextBuilder()
                    .loadTrustMaterial(null, (x509CertChain, authType) -> true)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            e.printStackTrace();
        }

        Header header = new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        List<Header> headers = Lists.newArrayList(header);

        RequestConfig.Builder requestBuilder = RequestConfig.custom();
        requestBuilder.setConnectTimeout(30000);
        requestBuilder.setConnectionRequestTimeout(30000);
        requestBuilder.setSocketTimeout(30000);

        assert sslContext != null;
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.INSTANCE)
                        .register("https", new SSLConnectionSocketFactory(sslContext,
                                NoopHostnameVerifier.INSTANCE))
                        .build()
        );
        connectionManager.setDefaultMaxPerRoute(maximumSockets);
        connectionManager.setMaxTotal(maximumSockets);

        client = HttpClients.custom()
                .setDefaultHeaders(headers)
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .setSSLContext(sslContext)
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestBuilder.build())
                .build();
    }

    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String doGet(String endpoint) {
        String res = null;
        HttpGet httpget = new HttpGet(App.BASE_URL + endpoint);
        CloseableHttpResponse response = null;
        try {
            response = client.execute(httpget);
            if (validateResponse(response)) {
            HttpEntity entity = response.getEntity();
            res = EntityUtils.toString(entity);
            EntityUtils.consume(entity);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }  finally {
            httpget.reset();
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return res;
    }

    private String doPost(String endpoint, JsonObject params) {
        String res = null;
        HttpPost httpPost = new HttpPost();
        httpPost.setURI(URI.create(App.BASE_URL + endpoint));
        try {
            httpPost.setEntity(new StringEntity(params.toString()));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            LOGGER.log(Level.WARNING, "doPost failed to set entity " + endpoint + " err: " + e.toString());
            httpPost.reset();
            return null;
        }

        CloseableHttpResponse response = null;
        try {
            response = client.execute(httpPost);
            HttpEntity entity = response.getEntity();
            if (!validateResponse(response))
                LOGGER.log(Level.WARNING, "doPost " + endpoint + " bad response: " + EntityUtils.toString(entity));
            else
                res = EntityUtils.toString(entity);
            EntityUtils.consume(entity);
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.log(Level.WARNING, "doPost failed to execute post " + endpoint + " err: " + e.toString());
        } finally {
            httpPost.reset();
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    e.printStackTrace();
                    LOGGER.log(Level.WARNING, "doPost failed close response " + endpoint + " err: " + e.toString());
                }
            }
        }

        return res;
    }

    /**
     * Returns all utxos for a list of addresses.
     * Note: This method does neither use nor update any caches!
     * 
     * @param coinTicker Fetch utxos from this coin
     * @param address    Fetch utxos from this address
     * @return JsonArray or null on error
     */
    public JsonArray getUtxosUncached(CoinTicker coinTicker, String[] addresses) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);

        JsonArray innerParams = new JsonArray();
        innerParams.add(CoinTickerUtils.tickerToString(coinTicker));
        innerParams.add(new Gson().toJsonTree(addresses).getAsJsonArray());

        JsonObject params = new JsonObject();
        params.addProperty("method", "getutxos");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getUtxosUncached " + coinInstance.getTicker() + " " + res);

        if (res == null) {
            LOGGER.log(Level.WARNING, "[httpclient] getUtxosUncached " + coinInstance.getTicker() + " null post result");
            return null;
        }

        JSONObject jsonObject = null;
        JSONArray utxoArr = null;
        try {
            jsonObject = new JSONObject(res);
            utxoArr = jsonObject.getJSONArray("utxos");
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (jsonObject == null || utxoArr == null) {
            if (jsonObject == null)
                LOGGER.log(Level.WARNING, "[httpclient] getUtxosUncached " + coinInstance.getTicker() + " null jsonObject");
            if (utxoArr == null)
                LOGGER.log(Level.WARNING, "[httpclient] getUtxosUncached " + coinInstance.getTicker() + " null utxoArr");
            return null;
        }

        JsonArray utxoList = new JsonArray();
        for (int i = 0; i < utxoArr.length(); i++) {
            JsonObject utxoJSON = new JsonObject();
            utxoJSON.addProperty("txid", utxoArr.getJSONObject(i).getString("txhash"));
            utxoJSON.addProperty("vout", utxoArr.getJSONObject(i).getInt("vout"));
            utxoJSON.addProperty("value", utxoArr.getJSONObject(i).getDouble("value"));
            utxoJSON.addProperty("spendable", true);

            String address = utxoArr.getJSONObject(i).getString("address");
            utxoJSON.addProperty("address", address);

            Address addr = Address.fromBase58(coinInstance.getNetworkParameters(), address);
            Script script = ScriptBuilder.createOutputScript(addr);
            utxoJSON.addProperty("scriptPubKey", new String(Hex.encode(script.getProgram())));

            int height = utxoArr.getJSONObject(i).getInt("block_number");
            int currentHeight = CoinInstance.getBlockCountByTicker(coinTicker);
            int confirmations = (currentHeight - height) + 1;
            if (height == 0)
                confirmations = 0;

            utxoJSON.addProperty("confirmations", confirmations);

            utxoList.add(utxoJSON);
        }

        return utxoList;
    }

    /**
     * Returns all utxos.
     * @param coinTicker Fetch utxos from this coin
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    public JsonArray getUtxos(CoinTicker coinTicker, int expiry) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        long lastFetchTime = lastFetchTimes.getOrDefault(lastFetchTimesKey(coinTicker, "getUtxos"), 0L);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFetchTime < expiry)
            return coinInstance.getAllUTXOS();

        ArrayList<String> utxoParams = coinInstance.getUTXOParams();
        if (utxoParams.size() == 0) {
            LOGGER.log(Level.WARNING, "[httpclient] getUtxos " + coinInstance.getTicker() + " null param size");
            return null;
        }

        JsonArray innerParams = new Gson().toJsonTree(utxoParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getutxos");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getUtxos " + coinInstance.getTicker() + " " + res);

        if (res == null) {
            LOGGER.log(Level.WARNING, "[httpclient] getUtxos " + coinInstance.getTicker() + " null post result");
            return null;
        }

        JSONObject jsonObject = null;
        JSONArray utxoArr = null;
        try {
            jsonObject = new JSONObject(res);
            utxoArr = jsonObject.getJSONArray("utxos");
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (jsonObject == null || utxoArr == null) {
            if (jsonObject == null)
                LOGGER.log(Level.WARNING, "[httpclient] getUtxos " + coinInstance.getTicker() + " null jsonObject");
            if (utxoArr == null)
                LOGGER.log(Level.WARNING, "[httpclient] getUtxos " + coinInstance.getTicker() + " null utxoArr");
            return null;
        }

        List<UTXO> utxoList = new ArrayList<>();
        for (int i = 0; i < utxoArr.length(); i++) {
            UTXO utxo = new UTXO(coinTicker,
                    utxoArr.getJSONObject(i).getString("address"),
                    utxoArr.getJSONObject(i).getString("txhash"),
                    utxoArr.getJSONObject(i).getInt("vout"),
                    utxoArr.getJSONObject(i).getInt("block_number"),
                    (long) Math.floor(utxoArr.getJSONObject(i).getDouble("value") * 100000000.0));

            utxoList.add(utxo);
        }

        // Update last fetch time
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getUtxos"), currentTime);

        coinInstance.processUtxos(utxoList);
        return coinInstance.getAllUTXOS();
    }

    public void getAllFees() {
        String res = doGet("/fees");

        if (res == null) return;

        JsonObject result = new Gson().fromJson(res, JsonObject.class);
        JsonObject fees = result.get("result").getAsJsonObject();

        for (CoinTicker coinTicker : CoinTicker.coins()) {
            CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
            String ticker = CoinTickerUtils.tickerToString(coinTicker);

            if (!fees.keySet().contains(ticker) || fees.get(ticker).isJsonNull()) {
                coinInstance.incrementUpdateFailures();
                continue;
            }

            double fee = fees.get(ticker).getAsDouble();

            coinInstance.addRelayFee(coinTicker, fee);

            if (logCount % 30 == 0)
                LOGGER.log(Level.INFO, "[httpclient] Got relayfee for currency " + ticker + " - " + fee);
            else
                LOGGER.log(Level.FINER, "[httpclient] Got relayfee for currency " + ticker + " - " + fee);
        }
        logCount += 1;
    }

    public JsonObject getRawTransaction(CoinTicker coinTicker, String txid, boolean verbose) {
        ArrayList<String> rawTxParams = new ArrayList<>();
        rawTxParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawTxParams.add(1, txid);
        rawTxParams.add(2, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawTxParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getrawtransaction");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getRawTransaction " + res);

        if (res == null) return null;

        return new Gson().fromJson(res, JsonObject.class);
    }

    public JsonObject getRawMempool(CoinTicker coinTicker, boolean verbose) {
        ArrayList<String> rawMempoolParams = new ArrayList<>();
        rawMempoolParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawMempoolParams.add(1, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawMempoolParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getrawmempool");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getRawMempool " + res);

        if (res == null) return null;

        return new Gson().fromJson(res, JsonObject.class);
    }

    public void getBlockCount(CoinTicker coinTicker) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        ArrayList<String> blockCountParams = new ArrayList<>();
        blockCountParams.add(0, CoinTickerUtils.tickerToString(coinTicker));

        JsonArray innerParams = new Gson().toJsonTree(blockCountParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getblockcount");
        params.add("params", innerParams);

        String res = doPost("/", params);

        if (res == null) return;

        JsonObject result = new Gson().fromJson(res, JsonObject.class);
        int blockCount = result.get("result").getAsInt();

        coinInstance.addBlockCount(coinTicker, blockCount);

        LOGGER.log(Level.FINER, "[httpclient] Got blockcount for currency " + coinTicker + " - " + blockCount);
    }

    public void getAllBlockCounts() {
        String res = doGet("/height");

        if (res == null) return;

        JsonObject result = new Gson().fromJson(res, JsonObject.class);
        JsonObject blockCounts = result.get("result").getAsJsonObject();

        for (CoinTicker coinTicker : CoinTicker.coins()) {
            CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
            String ticker = CoinTickerUtils.tickerToString(coinTicker);

            if (!blockCounts.keySet().contains(ticker) || blockCounts.get(ticker).isJsonNull()) {
                coinInstance.incrementUpdateFailures();
                continue;
            }

            int blockCount = blockCounts.get(ticker).getAsInt();

            coinInstance.addBlockCount(coinTicker, blockCount);
            coinInstance.resetUpdateFailures();

            LOGGER.log(Level.FINER, "[httpclient] Got blockcount for currency " + ticker + " - " + blockCount);
        }
    }

    public JsonObject getBlock(CoinTicker coinTicker, String hash, boolean verbose) {
        ArrayList<String> rawParams = new ArrayList<>();
        rawParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawParams.add(1, hash);
        rawParams.add(2, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getblock");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getBlock " + res);

        if (res == null) return null;

        return new Gson().fromJson(res, JsonObject.class);
    }

    public JsonObject getBlockHash(CoinTicker coinTicker, int height) {
        JsonArray innerParams = new JsonArray();
        innerParams.add(CoinTickerUtils.tickerToString(coinTicker));
        innerParams.add(height);

        JsonObject params = new JsonObject();
        params.addProperty("method", "getblockhash");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getBlockHash " + res);

        if (res == null) return null;

        return new Gson().fromJson(res, JsonObject.class);
    }

    public JsonObject getTransaction(CoinTicker coinTicker, String txid, boolean verbose) {
        ArrayList<String> rawParams = new ArrayList<>();
        rawParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawParams.add(1, txid);
        rawParams.add(2, String.valueOf(verbose));

        JsonArray innerParams = new Gson().toJsonTree(rawParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "gettransaction");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getTransaction " + res);

        if (res == null) return null;

        return new Gson().fromJson(res, JsonObject.class);
    }

    public JsonObject sendRawTransaction(CoinTicker coinTicker, String rawTx) {
        ArrayList<String> rawTxParams = new ArrayList<>();
        rawTxParams.add(0, CoinTickerUtils.tickerToString(coinTicker));
        rawTxParams.add(1, rawTx);

        JsonArray innerParams = new Gson().toJsonTree(rawTxParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "sendrawtransaction");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] sendRawTransaction " + res);

        if (res == null) return null;

        return new Gson().fromJson(res, JsonObject.class);
    }

    /**
     * Return all transactions associated with the coin.
     * @param coinTicker Coin
     * @param startTime Beginning of the time frame in unix time
     * @param endTime End of the time frame in unix time
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    public JsonArray getHistory(CoinTicker coinTicker, int startTime, int endTime, int expiry) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        long lastFetchTime = lastFetchTimes.getOrDefault(lastFetchTimesKey(coinTicker, "getHistory"), 0L);
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFetchTime < expiry)
            return filterHistory(coinInstance.getAllTransactions(), startTime, endTime);

        ArrayList<String> utxoParams = coinInstance.getUTXOParams();
        if (utxoParams.size() == 0) {
            LOGGER.log(Level.WARNING, "[httpclient] getHistory " + coinInstance.getTicker() + " null param size");
            return null;
        }

        JsonArray innerParams = new Gson().toJsonTree(utxoParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "gethistory");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getHistory " + coinInstance.getTicker() + " " + res);
        if (res == null) {
            LOGGER.log(Level.WARNING, "[httpclient] getHistory " + coinInstance.getTicker() + " null post result");
            return null;
        }

        JsonArray json = new Gson().fromJson(res, JsonArray.class);
        if (json == null) {
            LOGGER.log(Level.WARNING, "[httpclient] getHistory " + coinInstance.getTicker() + " null json");
            return null;
        }

        List<Transaction> historyList = new ArrayList<>();
        for (JsonElement elements : json) {
            for (JsonElement element : elements.getAsJsonArray()) {
                //LOGGER.log(Level.WARNING, "*** DEBUG *** [httpclient] getHistory " + element);
                JsonObject jsonObject = element.getAsJsonObject();

                List<String> fromAddresses = new Gson().fromJson(jsonObject.get("from_addresses"), new TypeToken<List<String>>() {
                }.getType());

                Transaction tx = new Transaction(coinTicker,
                        jsonObject.get("address").getAsString(),
                        jsonObject.get("txid").getAsString(),
                        jsonObject.get("blockhash").getAsString(),
                        jsonObject.get("vout").getAsInt(),
                        jsonObject.get("amount").getAsDouble(),
                        jsonObject.get("confirmations").getAsInt(),
                        jsonObject.get("blocktime").getAsInt(),
                        fromAddresses);
                tx.setCategory(jsonObject.get("category").getAsString());
                tx.setFee(jsonObject.get("fee").getAsDouble());

                historyList.add(tx);
            }
        }
        coinInstance.processHistoryTxs(historyList);

        // Return the latest transaction history
        JsonArray txs = coinInstance.getAllTransactions();
        if (txs == null) {
            LOGGER.log(Level.WARNING, "[httpclient] getHistory " + coinInstance.getTicker() + " null txs");
            return null;
        }

        // Update last fetch time
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getHistory"), currentTime);

        // Filter txs by time if time frame requested
        return filterHistory(txs, startTime, endTime);
    }

    /**
     * Return all transaction hashes associated with the coin.
     * @param coinTicker Coin
     * @param startTime Beginning of the time frame in unix time
     * @param endTime End of the time frame in unix time
     * @param expiry Time in milliseconds until cache expires
     * @return JsonArray or null on error
     */
    public JsonArray getTransactionHistory(CoinTicker coinTicker, int startTime, int endTime, int expiry) {
        CoinInstance coinInstance = CoinInstance.getInstance(coinTicker);
        long lastFetchTime = lastFetchTimes.getOrDefault(lastFetchTimesKey(coinTicker, "getAddressHistory"), 0L);
        long currentTime = System.currentTimeMillis() / 1000;
        if ((currentTime - lastFetchTime) < expiry)
            return filterHistory(coinInstance.getAllTransactions(), startTime, endTime);

        ArrayList<String> utxoParams = coinInstance.getUTXOParams();
        if (utxoParams.size() == 0) {
            LOGGER.log(Level.WARNING, "[httpclient] getAddressHistory " + coinInstance.getTicker() + " null param size");
            return null;
        }

        JsonArray innerParams = new Gson().toJsonTree(utxoParams).getAsJsonArray();

        JsonObject params = new JsonObject();
        params.addProperty("method", "getaddresshistory");
        params.add("params", innerParams);

        String res = doPost("/", params);
        LOGGER.log(Level.FINER, "[httpclient] getAddressHistory " + coinInstance.getTicker() + " " + res);
        if (res == null) {
            LOGGER.log(Level.WARNING, "[httpclient] getAddressHistory " + coinInstance.getTicker() + " null post result");
            return null;
        }

        JsonArray json = new Gson().fromJson(res, JsonArray.class);
        if (json == null) {
            LOGGER.log(Level.WARNING, "[httpclient] getAddressHistory " + coinInstance.getTicker() + " null json");
            return null;
        }

        List<Transaction> historyList = new ArrayList<>();
        for (JsonElement elements : json) {
            for (JsonElement element : elements.getAsJsonArray()) {
                //LOGGER.log(Level.WARNING, "*** DEBUG *** [httpclient] getAddressHistory " + element );
                JsonObject jsonObject = element.getAsJsonObject();

                String txid = jsonObject.get("tx_hash").getAsString();
                JsonObject rawTransaction = null;

                int fails = 1;
                while (fails > 0) {
                    if (fails >= 5)
                        fails = 0;

                    try {
                        rawTransaction = getRawTransaction(coinTicker, txid, true);

                        if (rawTransaction != null && !rawTransaction.get("result").isJsonNull()) {
                            rawTransaction = rawTransaction.getAsJsonObject("result");
                            fails = 0;

                            break;
                        } else
                            ++fails;
                    } catch (Exception e) {
                        e.printStackTrace();
                        ++fails;
                    }
                }

                if (fails != 0)
                    continue;

                for (JsonElement vin : rawTransaction.get("vin").getAsJsonArray()) {
                    String vinTxid = vin.getAsJsonObject().get("txid").getAsString();
                    int voutInt = vin.getAsJsonObject().get("vout").getAsInt();

                    JsonObject voutRawTransaction = null;

                    fails = 1;
                    while (fails > 0) {
                        if (fails >= 5)
                            fails = 0;

                        try {
                            voutRawTransaction = getRawTransaction(coinTicker, vinTxid, true);

                            if (voutRawTransaction != null && !voutRawTransaction.get("result").isJsonNull()) {
                                voutRawTransaction = voutRawTransaction.getAsJsonObject("result");
                                fails = 0;

                                break;
                            } else
                                ++fails;
                        } catch (Exception e) {
                            e.printStackTrace();
                            ++fails;
                        }
                    }

                    if (fails != 0)
                        continue;

                    JsonObject vout = voutRawTransaction.get("vout").getAsJsonArray().get(voutInt).getAsJsonObject();
                    JsonObject scriptPubKey = vout.getAsJsonObject("scriptPubKey");

                    if ((scriptPubKey == null || scriptPubKey.isJsonNull()) || scriptPubKey.get("addresses").isJsonNull())
                        continue;

                    for (JsonElement addressElement : scriptPubKey.getAsJsonArray("addresses")) {
                        String address = addressElement.getAsString();

                        for (AddressBalance addressBalance : coinInstance.getAddressKeyPairs()) {
                            String utxoAddress = addressBalance.getAddress().toBase58();

                            if (utxoAddress.equals(address)) {
                                List<String> fromAddresses = new ArrayList<>();

                                Transaction tx = new Transaction(coinTicker,
                                        address,
                                        txid,
                                        rawTransaction.get("blockhash").getAsString(),
                                        voutInt,
                                        vout.get("value").getAsDouble(),
                                        rawTransaction.get("confirmations").getAsInt(),
                                        rawTransaction.get("blocktime").getAsInt(),
                                        fromAddresses);
                                tx.setCategory("send");
                                tx.setFee(0.0);

                                historyList.add(tx);
                            }
                        }
                    }
                }


                for (JsonElement vout : rawTransaction.get("vout").getAsJsonArray()) {
                    JsonObject scriptPubKey = vout.getAsJsonObject().getAsJsonObject("scriptPubKey");

                    try {
                        if (scriptPubKey.isJsonNull() || scriptPubKey.get("addresses").isJsonNull())
                            continue;
                    } catch (Exception e) {
                        continue;
                    }

                    for (JsonElement addressElement : scriptPubKey.getAsJsonArray("addresses")) {
                        String address = addressElement.getAsString();

                        for (AddressBalance addressBalance : coinInstance.getAddressKeyPairs()) {
                            String utxoAddress = addressBalance.getAddress().toBase58();

                            if (utxoAddress.equals(address)) {
                                List<String> fromAddresses = new ArrayList<>();

                                Transaction tx = new Transaction(coinTicker,
                                        address,
                                        txid,
                                        rawTransaction.get("blockhash").getAsString(),
                                        vout.getAsJsonObject().get("n").getAsInt(),
                                        vout.getAsJsonObject().get("value").getAsDouble(),
                                        rawTransaction.get("confirmations").getAsInt(),
                                        rawTransaction.get("blocktime").getAsInt(),
                                        fromAddresses);
                                tx.setCategory("receive");
                                tx.setFee(0.0);

                                historyList.add(tx);
                            }
                        }
                    }
                }
            }
        }
        coinInstance.processHistoryTxs(historyList);

        // Return the latest transaction history
        JsonArray txs = coinInstance.getAllTransactions();
        if (txs == null) {
            LOGGER.log(Level.WARNING, "[httpclient] getAddressHistory " + coinInstance.getTicker() + " null txs");
            return null;
        }

        // Update last fetch time
        lastFetchTimes.put(lastFetchTimesKey(coinTicker, "getAddressHistory"), currentTime);

        // Filter txs by time if time frame requested
        return filterHistory(txs, startTime, endTime);
    }

    private boolean validateResponse(HttpResponse response) {
        return response.getStatusLine().getStatusCode() == 200 && response.getEntity().getContentLength() != 0;
    }

    /**
     * Filters the transaction array in place. This does not make a copy but modifies
     * the existing list.
     * @param txs List to filter
     * @param startTime Transaction on or after this time
     * @param endTime Transaction on or before this time
     * @return Filtered transaction list
     */
    private JsonArray filterHistory(JsonArray txs, int startTime, int endTime) {
        if (endTime <= 0)
            return txs;
        Iterator<JsonElement> it = txs.iterator();
        while (it.hasNext()) {
            JsonObject tx = it.next().getAsJsonObject();
            int txTime = tx.get("time").getAsInt();
            if (txTime < startTime || txTime > endTime)
                it.remove();
        }
        return txs;
    }

    /**
     * Returns the key used with last fetch times.
     * @param ticker Coin
     * @param method Storage key name
     * @return Storage key
     */
    private String lastFetchTimesKey(CoinTicker ticker, String method) {
        return method + ":" + ticker.name();
    }
}
