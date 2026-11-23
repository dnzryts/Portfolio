package com.uliptracker;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password-encrypted backups (PBKDF2 + AES-GCM) written to a user-chosen folder. */
public final class Backup {
    private Backup() { }

    private static final byte[] MAGIC = "PPBK1".getBytes(StandardCharsets.US_ASCII);
    private static final int ITER = 60000;

    public static boolean isBackup(byte[] d) {
        return d.length > MAGIC.length + 28 && Arrays.equals(Arrays.copyOf(d, MAGIC.length), MAGIC);
    }

    private static SecretKeySpec key(String pw, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] k = f.generateSecret(new PBEKeySpec(pw.toCharArray(), salt, ITER, 256)).getEncoded();
        return new SecretKeySpec(k, "AES");
    }

    public static byte[] encrypt(byte[] plain, String pw) throws Exception {
        SecureRandom r = new SecureRandom();
        byte[] salt = new byte[16], iv = new byte[12];
        r.nextBytes(salt); r.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(pw, salt), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[MAGIC.length + 16 + 12 + ct.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(salt, 0, out, MAGIC.length, 16);
        System.arraycopy(iv, 0, out, MAGIC.length + 16, 12);
        System.arraycopy(ct, 0, out, MAGIC.length + 28, ct.length);
        return out;
    }

    public static byte[] decrypt(byte[] data, String pw) throws Exception {
        if (!isBackup(data)) throw new Exception("Not a backup file");
        byte[] salt = Arrays.copyOfRange(data, MAGIC.length, MAGIC.length + 16);
        byte[] iv = Arrays.copyOfRange(data, MAGIC.length + 16, MAGIC.length + 28);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(pw, salt), new GCMParameterSpec(128, iv));
        return c.doFinal(data, MAGIC.length + 28, data.length - MAGIC.length - 28);
    }

    /** Create or overwrite `name` inside the persisted folder `tree`. */
    public static void write(Context c, Uri tree, String name, byte[] data) throws Exception {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Uri target = null;
        Cursor q = c.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (q != null) {
            while (q.moveToNext()) if (name.equals(q.getString(1))) { target = DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0)); break; }
            q.close();
        }
        if (target == null) target = DocumentsContract.createDocument(c.getContentResolver(), dir, "application/octet-stream", name);
        if (target == null) throw new Exception("Couldn't create the backup file");
        OutputStream o = c.getContentResolver().openOutputStream(target, "wt");
        if (o == null) throw new Exception("Couldn't open the backup file");
        o.write(data);
        o.close();
    }
}
