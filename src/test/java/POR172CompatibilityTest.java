import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.subgraph.orchid.encoders.Hex;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.ConfigHelper;
import io.cloudchains.app.util.UTXO;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class POR172CompatibilityTest {
    private static final String HANDLER =
            "io.cloudchains.app.net.api.http.server.HTTPServerHandler";
    private static final String INPUT_TXID =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final long CUSTOM_SEQUENCE = 0x12345678L;
    private static final long CUSTOM_LOCKTIME = 500_000_000L;

    @Test
    void createRawTransactionPreservesSequencesAndStandardLocktime(@TempDir Path directory)
            throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            AddressBalance destination = coin.generateAddress(false);
            JsonArray inputs = new JsonArray();
            JsonObject input = new JsonObject();
            input.addProperty("txid", INPUT_TXID);
            input.addProperty("vout", 1);
            input.addProperty("sequence", CUSTOM_SEQUENCE);
            inputs.add(input);
            JsonObject secondInput = new JsonObject();
            secondInput.addProperty("txid", INPUT_TXID);
            secondInput.addProperty("vout", 2);
            secondInput.addProperty("sequence", 7);
            inputs.add(secondInput);

            JsonObject outputs = new JsonObject();
            outputs.addProperty(destination.getAddress().toBase58(), 0.001);

            JsonArray params = new JsonArray();
            params.add(inputs);
            params.add(outputs);
            params.add(CUSTOM_LOCKTIME);

            JsonObject response = invoke(coin, "createrawtransaction", params);
            assertSuccessful(response);

            Transaction transaction = decode(coin, response.get("result").getAsString());
            assertEquals(CUSTOM_LOCKTIME, transaction.getLockTime());
            assertEquals(CUSTOM_SEQUENCE, transaction.getInput(0).getSequenceNumber());
            assertEquals(7, transaction.getInput(1).getSequenceNumber());
        } finally {
            stopCoin(coin);
        }
    }

    @Test
    void createRawTransactionRejectsOutOfRangeLocktime(@TempDir Path directory)
            throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            AddressBalance destination = coin.generateAddress(false);
            JsonArray params = createParams(destination, 0x1_0000_0000L);

            JsonObject response = invoke(coin, "createrawtransaction", params);

            assertError(response, -1);
        } finally {
            stopCoin(coin);
        }
    }

    @Test
    void signRawTransactionPreservesStructureAndSignsOwnedInput(@TempDir Path directory)
            throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            AddressBalance owned = coin.generateAddress(false);
            coin.getAddressKeyPairs().get(coin.getAddressKeyPairs().size() - 1).addUtxo(
                    new UTXO(CoinTicker.LITECOIN, owned.getAddress().toBase58(), INPUT_TXID,
                            0, 100, 200_000));

            Transaction transaction = new Transaction(coin.getNetworkParameters());
            transaction.setVersion(2);
            transaction.setLockTime(CUSTOM_LOCKTIME);
            TransactionInput input = transaction.addInput(Sha256Hash.wrap(INPUT_TXID), 0,
                    ScriptBuilder.createInputScript(null));
            input.setSequenceNumber(CUSTOM_SEQUENCE);
            transaction.addOutput(Coin.valueOf(100_000), owned.getAddress());

            JsonArray params = new JsonArray();
            params.add(new String(Hex.encode(transaction.bitcoinSerialize())));
            JsonObject response = invoke(coin, "signrawtransaction", params);
            assertSuccessful(response);

            JsonObject result = response.getAsJsonObject("result");
            assertTrue(result.get("complete").getAsBoolean());
            Transaction signed = decode(coin, result.get("hex").getAsString());
            assertEquals(2, signed.getVersion());
            assertEquals(CUSTOM_LOCKTIME, signed.getLockTime());
            assertEquals(1, signed.getInputs().size());
            assertEquals(CUSTOM_SEQUENCE, signed.getInput(0).getSequenceNumber());
            assertEquals(1, signed.getOutputs().size());
            assertTrue(signed.getInput(0).getScriptSig().getProgram().length > 0);
        } finally {
            stopCoin(coin);
        }
    }

    @Test
    void signRawTransactionRejectsUnownedInput(@TempDir Path directory) throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            AddressBalance destination = coin.generateAddress(false);
            Transaction transaction = new Transaction(coin.getNetworkParameters());
            transaction.addInput(Sha256Hash.wrap(INPUT_TXID), 0,
                    ScriptBuilder.createInputScript(null));
            transaction.addOutput(Coin.valueOf(100_000), destination.getAddress());

            JsonArray params = new JsonArray();
            params.add(new String(Hex.encode(transaction.bitcoinSerialize())));
            JsonObject response = invoke(coin, "signrawtransaction", params);

            assertError(response, -5);
        } finally {
            stopCoin(coin);
        }
    }

    @Test
    void disabledPrivateKeyMethodsRemainUnavailable(@TempDir Path directory) throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            for (String method : new String[] {"importprivkey", "dumpprivkey"}) {
                JsonObject response = invoke(coin, method, new JsonArray());
                assertError(response, -32601);
            }
        } finally {
            stopCoin(coin);
        }
    }

    @Test
    void signMessageRemainsBoundToAnOwnedAddress(@TempDir Path directory) throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            AddressBalance owned = coin.generateAddress(false);
            JsonArray ownedParams = new JsonArray();
            ownedParams.add(owned.getAddress().toBase58());
            ownedParams.add("POR-172 synthetic message");
            JsonObject ownedResponse = invoke(coin, "signmessage", ownedParams);
            assertSuccessful(ownedResponse);
            assertTrue(ownedResponse.get("result").getAsString().length() > 0);

            String unownedAddress = new ECKey().toAddress(coin.getNetworkParameters()).toBase58();
            JsonArray unownedParams = new JsonArray();
            unownedParams.add(unownedAddress);
            unownedParams.add("POR-172 synthetic message");
            JsonObject unownedResponse = invoke(coin, "signmessage", unownedParams);
            assertError(unownedResponse, -5);
        } finally {
            stopCoin(coin);
        }
    }

    private static JsonArray createParams(AddressBalance destination, long locktime) {
        JsonArray inputs = new JsonArray();
        JsonObject input = new JsonObject();
        input.addProperty("txid", INPUT_TXID);
        input.addProperty("vout", 0);
        inputs.add(input);

        JsonObject outputs = new JsonObject();
        outputs.addProperty(destination.getAddress().toBase58(), 0.001);

        JsonArray params = new JsonArray();
        params.add(inputs);
        params.add(outputs);
        params.add(locktime);
        return params;
    }

    private static CoinInstance startCoin(Path directory) {
        ConfigHelper.CONFIG_DIR = directory.toString();
        CoinInstance.getCoinInstances().clear();
        CoinInstance coin = CoinInstance.getInstance(CoinTicker.LITECOIN);
        assertNull(coin.init("Por172^test", null, false));
        return coin;
    }

    private static void stopCoin(CoinInstance coin) {
        if (coin != null)
            coin.deinit();
        CoinInstance.getCoinInstances().clear();
    }

    private static JsonObject invoke(CoinInstance coin, String method, JsonArray params)
            throws Exception {
        Class<?> handlerClass = Class.forName(HANDLER);
        Constructor<?> constructor = handlerClass.getDeclaredConstructor(CoinInstance.class);
        constructor.setAccessible(true);
        Object handler = constructor.newInstance(coin);
        Method getResponse = handlerClass.getDeclaredMethod("getResponse", String.class, JsonArray.class);
        getResponse.setAccessible(true);
        JsonObject response = (JsonObject) getResponse.invoke(handler, method, params);
        assertNotNull(response);
        return response;
    }

    private static Transaction decode(CoinInstance coin, String hex) {
        return new Transaction(coin.getNetworkParameters(), Hex.decode(hex));
    }

    private static void assertSuccessful(JsonObject response) {
        assertTrue(response.get("error").isJsonNull(), response.toString());
        assertTrue(response.has("result"), response.toString());
    }

    private static void assertError(JsonObject response, int code) {
        assertEquals(code, response.getAsJsonObject("error").get("code").getAsInt(),
                response.toString());
    }
}
