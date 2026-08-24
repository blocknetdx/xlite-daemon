package io.cloudchains.app.crypto;

import com.google.common.base.Joiner;
import com.subgraph.orchid.encoders.Base64;
import io.cloudchains.app.util.ConfigHelper;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.DeterministicSeed;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;


public class KeyHandler {
    private final static LogManager LOGMANAGER = LogManager.getLogManager();
    private final static Logger LOGGER = LOGMANAGER.getLogger(Logger.GLOBAL_LOGGER_NAME);
    private static final Object KEY_FILE_LOCK = new Object();
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(java.time.ZoneOffset.UTC);

    private ECKey ecKey;

    public KeyHandler(ECKey key) {
        this.ecKey = key;
    }

    public ECKey getBaseECKey() {
        return this.ecKey;
    }

    public ECKey getPublicKey() {
        return ECKey.fromPublicOnly(this.ecKey.getPubKey());
    }


    public static boolean existsBaseECKeyFromLocal() {
        synchronized (KEY_FILE_LOCK) {
            String keyPath = ConfigHelper.getLocalDataDirectory() + "key.dat";
            File keyFile = new File(keyPath);
            recoverKeyFile(keyFile);
            if (Files.exists(keyFile.toPath(), LinkOption.NOFOLLOW_LINKS))
                ConfigHelper.applyOwnerOnlyFilePermissions(keyFile);

            return Files.exists(keyFile.toPath(), LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static String encryptBaseSeed(String passphrase, byte[] seedBytes, byte[] salt) {
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, 16384, 256);
            SecretKey tmp = skf.generateSecret(spec);
            SecretKey key = new SecretKeySpec(tmp.getEncoded(), "AES");

            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(seedBytes);
            byte[] encryptedValue = Base64.encode(encrypted);
            return new String(encryptedValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static List<String> getBaseSeed(String passphrase) {
        File keyFile = new File(ConfigHelper.getLocalDataDirectory() + "key.dat");

        if (existsBaseECKeyFromLocal()) {
            synchronized (KEY_FILE_LOCK) {
                File lockFile = new File(keyFile.getPath() + ".lock");
                ConfigHelper.ensureOwnerOnlyFile(lockFile);
                try (FileChannel lockChannel = FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = lockChannel.lock()) {
                    BufferedReader bufferedReader = new BufferedReader(new StringReader(
                            ConfigHelper.readOwnerOnlyFile(keyFile)));
                    String saltB64 = bufferedReader.readLine();
                    String seedEncryptedB64 = bufferedReader.readLine();

                    byte[] salt = Base64.decode(saltB64);
                    byte[] seedEncrypted = Base64.decode(seedEncryptedB64);

                    SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
                    PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, 16384, 256);
                    SecretKey tmp = skf.generateSecret(spec);
                    SecretKey key = new SecretKeySpec(tmp.getEncoded(), "AES");

                    Cipher cipher = Cipher.getInstance("AES");
                    cipher.init(Cipher.DECRYPT_MODE, key);
                    String seed = new String(cipher.doFinal(seedEncrypted));

                    return Arrays.asList(seed.split(" "));
                } catch (Exception e) {
                    LOGGER.log(Level.FINER, "Error while obtaining base seed: " + e);
                    LOGGER.log(Level.FINER, "Bad password.");

                    return null;
                }
            }
        } else {

            DeterministicSeed seed = null;
            seed = new DeterministicSeed(new SecureRandom(), 128, "", System.currentTimeMillis() / 1000);

            String mnemonic = Joiner.on(" ").join(Objects.requireNonNull(seed.getMnemonicCode()));

            if (writeInitialData(keyFile, mnemonic, passphrase)) {
                return seed.getMnemonicCode();
            } else {
                return null;
            }
        }
    }

    public static boolean importFromMnemonic(List<String> mnemonicList, String passphrase) {
        File keyFile = new File(ConfigHelper.getLocalDataDirectory() + "key.dat");
        byte[] entropy;

        try {
            MnemonicCode mnemonicCode = new MnemonicCode();
            entropy = mnemonicCode.toEntropy(mnemonicList);
        } catch (IOException | MnemonicException.MnemonicWordException | MnemonicException.MnemonicChecksumException | MnemonicException.MnemonicLengthException e) {
            e.printStackTrace();
            return false;
        }

        DeterministicSeed seed = new DeterministicSeed(entropy , "", System.currentTimeMillis() / 1000);

        String mnemonic = Joiner.on(" ").join(Objects.requireNonNull(seed.getMnemonicCode()));

        if (!seed.getMnemonicCode().toString().equals(mnemonicList.toString()))
            return false;

        return writeInitialData(keyFile, mnemonic, passphrase);
    }

    public static byte[] mnemonicToEntropy(List<String> mnemonicList) {
        byte[] entropy = null;

        try {
            MnemonicCode mnemonicCode = new MnemonicCode();
            entropy = mnemonicCode.toEntropy(mnemonicList);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return entropy;
    }

    private static File findRenameFile() {
        for (int i = 0; i < 100; i++) {
            File file = new File(ConfigHelper.getLocalDataDirectory() + "key-" + i + ".dat");

            if (!file.exists()) {
                return file;
            }
        }

        return null;
    }

    private static boolean writeInitialData(File keyFile, String mnemonic, String passphrase) {
        synchronized (KEY_FILE_LOCK) {
            File lockFile = new File(keyFile.getPath() + ".lock");
            ConfigHelper.ensureOwnerOnlyFile(lockFile);

            try (FileChannel lockChannel = FileChannel.open(lockFile.toPath(), StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = lockChannel.lock()) {
                Path active = keyFile.toPath();
                boolean existingKey = Files.exists(active, LinkOption.NOFOLLOW_LINKS);
                if (existingKey)
                    ConfigHelper.ensureOwnerOnlyFile(keyFile);

                byte[] salt = new byte[20];
                new SecureRandom().nextBytes(salt);
                String encryptedSeed;
                try {
                    encryptedSeed = encryptBaseSeed(passphrase, mnemonic.getBytes(StandardCharsets.UTF_8), salt);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.FINER, "Unable to encrypt wallet data securely.");
                    return false;
                }

                Path temporary = writeKeyTemporary(active.getParent(), salt, encryptedSeed);
                try {
                    if (existingKey)
                        createKeyBackup(active);
                    replaceKeyAtomically(temporary, active);
                    temporary = null;
                    return true;
                } finally {
                    if (temporary != null) {
                        try {
                            Files.deleteIfExists(temporary);
                        } catch (IOException cleanupError) {
                            // The active key remains unchanged; clean up on a later startup.
                        }
                    }
                }
            } catch (AccessDeniedException e) {
                throw new IllegalStateException("Unable to persist wallet data securely.", e);
            } catch (IOException | OverlappingFileLockException e) {
                LOGGER.log(Level.FINER, "Unable to persist wallet data securely.");
                return false;
            }
        }
    }

    private static Path writeKeyTemporary(Path parent, byte[] salt, String encryptedSeed) throws IOException {
        if (parent == null)
            throw new IOException("Wallet key has no parent directory.");

        Path temporary = Files.createTempFile(parent, ".key.dat.por171-", ".tmp",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
             BufferedWriter writer = new BufferedWriter(Channels.newWriter(
                     channel, StandardCharsets.UTF_8.newEncoder(), -1))) {
            writer.write(new String(Base64.encode(salt), StandardCharsets.UTF_8));
            writer.newLine();
            writer.write(encryptedSeed);
            writer.newLine();
            writer.flush();
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The temporary file is owner-only and harmless if cleanup is delayed.
            }
            throw e;
        }
        ConfigHelper.applyOwnerOnlyFilePermissions(temporary.toFile());
        return temporary;
    }

    private static void createKeyBackup(Path active) throws IOException {
        Path parent = active.getParent();
        if (parent == null)
            throw new IOException("Wallet key has no parent directory.");

        Path backups = parent.resolve("backups");
        ConfigHelper.ensureOwnerOnlyDirectory(backups.toFile());
        Path temporary = Files.createTempFile(backups, ".key-backup.por171-", ".tmp",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
        try {
            copyOwnerOnlyFile(active, temporary);
            ConfigHelper.applyOwnerOnlyFilePermissions(temporary.toFile());

            for (int attempt = 0; attempt < 10000; attempt++) {
                Path backup = backups.resolve("key-" + BACKUP_TIMESTAMP.format(Instant.now()) + "-"
                        + UUID.randomUUID() + ".dat");
                try {
                    // A hard-link create is atomic and never replaces an existing backup path.
                    createNonOverwritingBackupLink(backup, temporary);
                    Files.delete(temporary);
                    temporary = null;
                    ConfigHelper.applyOwnerOnlyFilePermissions(backup.toFile());
                    forceDirectory(backups);
                    return;
                } catch (FileAlreadyExistsException ignored) {
                    // Retry with a fresh random name. The existing backup is never overwritten.
                } catch (UnsupportedOperationException e) {
                    throw new IOException("Non-overwriting backups are unavailable.", e);
                }
            }
            throw new IOException("Unable to allocate a unique wallet backup path.");
        } finally {
            if (temporary != null)
                Files.deleteIfExists(temporary);
        }
    }

    private static void createNonOverwritingBackupLink(Path backup, Path temporary) throws IOException {
        Files.createLink(backup, temporary);
    }

    private static void copyOwnerOnlyFile(Path source, Path target) throws IOException {
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (before.fileKey() == null)
            throw new IOException("Wallet key identity is unavailable.");
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel output = FileChannel.open(target, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            long size = input.size();
            long position = 0;
            while (position < size) {
                long copied = input.transferTo(position, size - position, output);
                if (copied <= 0)
                    throw new IOException("Unable to copy wallet key safely.");
                position += copied;
            }
            output.force(true);
        }
        BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(before.fileKey(), after.fileKey()))
            throw new IOException("Wallet key was replaced while being copied.");
    }

    private static void replaceKeyAtomically(Path temporary, Path active) throws IOException {
        try {
            Files.move(temporary, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Atomic wallet replacement is unavailable.", e);
        }
        try {
            forceDirectory(active.getParent());
        } catch (IOException e) {
            // The replacement is already committed; do not report a false rotation failure.
            LOGGER.log(Level.FINER, "Wallet replacement committed; directory durability could not be forced.");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private static void recoverKeyFile(File keyFile) {
        Path active = keyFile.toPath();
        if (Files.exists(active, LinkOption.NOFOLLOW_LINKS))
            return;

        Path parent = active.getParent();
        if (parent == null)
            return;
        Path backups = parent.resolve("backups");
        if (!Files.exists(backups, LinkOption.NOFOLLOW_LINKS))
            return;
        if (!Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Wallet backup path is not a secure directory.");

        try (java.util.stream.Stream<Path> paths = Files.list(backups)) {
            List<Path> candidates = paths
                    .filter(path -> path.getFileName().toString().startsWith("key-"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder()))
                    .toList();
            for (Path candidate : candidates) {
                try {
                    ConfigHelper.ensureOwnerOnlyFile(candidate.toFile());
                    if (!hasKeyMaterial(candidate))
                        continue;
                    Path temporary = Files.createTempFile(parent, ".key.dat.recovery-", ".tmp",
                            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                    java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
                    try {
                        copyOwnerOnlyFile(candidate, temporary);
                        ConfigHelper.applyOwnerOnlyFilePermissions(temporary.toFile());
                        replaceKeyAtomically(temporary, active);
                        temporary = null;
                        return;
                    } finally {
                        if (temporary != null)
                            Files.deleteIfExists(temporary);
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.FINER, "Unable to recover wallet key from a secure backup.");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to inspect wallet backups securely.", e);
        }
    }

    private static boolean hasKeyMaterial(Path path) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8))) {
            String salt = reader.readLine();
            String encrypted = reader.readLine();
            return salt != null && !salt.isEmpty() && encrypted != null && !encrypted.isEmpty();
        }
    }

    public static List<String> getMnemonicFromString(String mnemonic) {
        return Arrays.asList(mnemonic.split(" "));
    }

    public static int calculatePasswordStrength(String password){
        // Password must be greater than 8 characters, contain at least one digit, one lowercase letter, one uppercase letter and one special character.

        int totalScore = 0;

        if( password.length() < 8 )
            return 0;
        else if( password.length() >= 10 )
            totalScore += 2;
        else
            totalScore += 1;

        //if it contains one digit, add 2 to total score
        if( password.matches("(?=.*[0-9]).*") )
            totalScore += 2;

        //if it contains one lower case letter, add 2 to total score
        if( password.matches("(?=.*[a-z]).*") )
            totalScore += 2;

        //if it contains one upper case letter, add 2 to total score
        if( password.matches("(?=.*[A-Z]).*") )
            totalScore += 2;

        //if it contains one special character, add 2 to total score
        if( password.matches("(?=.*[~!@#$%^&*()_-]).*") )
            totalScore += 2;

        return totalScore;
    }
}
