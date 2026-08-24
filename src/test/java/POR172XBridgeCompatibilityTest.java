import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.cloudchains.app.net.CoinInstance;
import io.cloudchains.app.net.CoinTicker;
import io.cloudchains.app.util.AddressBalance;
import io.cloudchains.app.util.ConfigHelper;
import io.cloudchains.app.util.UTXO;
import org.bitcoinj.core.ECKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class POR172XBridgeCompatibilityTest {
    private static final String HANDLER =
            "io.cloudchains.app.net.api.http.server.HTTPServerHandler";
    private static final String INPUT_TXID =
            "0000000000000000000000000000000000000000000000000000000000000001";

    @Test
    void walletOwnedSelfAddressProofSigns(@TempDir Path directory) throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            String ownedAddress = coin.generateAddress(false).getAddress().toBase58();
            JsonObject response = invoke(coin, "signmessage",
                    signMessageParams(ownedAddress, ownedAddress));

            assertSuccessful(response);
            assertTrue(response.get("result").getAsString().length() > 0);
        } finally {
            stopCoin(coin);
        }
    }

    @Test
    void coreUtxoEntryProofRemainsAccepted(@TempDir Path directory) throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            AddressBalance owned = coin.generateAddress(false);
            String ownedAddress = owned.getAddress().toBase58();
            owned.addUtxo(new UTXO(CoinTicker.LITECOIN, ownedAddress, INPUT_TXID,
                    7, 100, 100_000));

            String message = INPUT_TXID + ":7:0.001:" + ownedAddress;
            JsonObject response = invoke(coin, "signmessage",
                    signMessageParams(ownedAddress, message));

            assertSuccessful(response);
            assertTrue(response.get("result").getAsString().length() > 0);
        } finally {
            stopCoin(coin);
        }
    }

    @Test
    void arbitraryAndWrongAddressMessagesAreRejected(@TempDir Path directory) throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            String ownedAddress = coin.generateAddress(false).getAddress().toBase58();
            String anotherOwnedAddress = coin.generateAddress(false).getAddress().toBase58();

            assertError(invoke(coin, "signmessage",
                    signMessageParams(ownedAddress, "POR-172 arbitrary message")), -1);
            assertError(invoke(coin, "signmessage",
                    signMessageParams(ownedAddress, anotherOwnedAddress)), -1);
        } finally {
            stopCoin(coin);
        }
    }

    @Test
    void nonWalletAddressIsRejectedBeforeSelfProof(@TempDir Path directory) throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            String nonWalletAddress = new ECKey()
                    .toAddress(coin.getNetworkParameters()).toBase58();

            assertError(invoke(coin, "signmessage",
                    signMessageParams(nonWalletAddress, nonWalletAddress)), -5);
        } finally {
            stopCoin(coin);
        }
    }

    @Test
    void signingSecurityBoundariesRemainRestricted() throws Exception {
        String content = Files.readString(Path.of(
                "src/main/java/io/cloudchains/app/net/api/http/server/HTTPServerHandler.java"));
        int signMessageStart = content.indexOf("case \"signmessage\"");
        int balanceLookup = content.indexOf(
                "AddressBalance address = coin.getAddressBalance(addr)", signMessageStart);
        int proofBranch = content.indexOf(
                "if (!addr.equals(message) && !isCoreUtxoEntryMessage(message, addr))",
                balanceLookup);
        int signMessageEnd = content.indexOf("case \"verifymessage\"", signMessageStart);
        String signMessage = content.substring(signMessageStart, signMessageEnd);
        String privateKeyMethods = content.substring(
                content.indexOf("case \"importprivkey\""), signMessageStart);

        assertTrue(signMessageStart >= 0);
        assertTrue(balanceLookup > signMessageStart);
        assertTrue(proofBranch > balanceLookup);
        assertTrue(signMessage.contains("isCoreUtxoEntryMessage(message, addr)"));
        assertTrue(privateKeyMethods.contains("Private-key RPC methods are disabled."));
        assertFalse(privateKeyMethods.contains("coin.importPrivateKey"));
        assertFalse(privateKeyMethods.contains("getPrivateKey()"));
    }

    @Test
    void privateKeyRpcMethodsRemainUnavailable(@TempDir Path directory) throws Exception {
        CoinInstance coin = startCoin(directory);
        try {
            for (String method : new String[] {"importprivkey", "dumpprivkey"}) {
                assertError(invoke(coin, method, new JsonArray()), -32601);
            }
        } finally {
            stopCoin(coin);
        }
    }

    private static JsonArray signMessageParams(String address, String message) {
        JsonArray params = new JsonArray();
        params.add(address);
        params.add(message);
        return params;
    }

    private static CoinInstance startCoin(Path directory) {
        ConfigHelper.CONFIG_DIR = directory.toString();
        CoinInstance.getCoinInstances().clear();
        CoinInstance coin = CoinInstance.getInstance(CoinTicker.LITECOIN);
        assertNotNull(coin);
        assertTrue(coin.init("Por172^test", null, false) == null);
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
        Method getResponse = handlerClass.getDeclaredMethod("getResponse", String.class,
                JsonArray.class);
        getResponse.setAccessible(true);
        JsonObject response = (JsonObject) getResponse.invoke(handler, method, params);
        assertNotNull(response);
        return response;
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
