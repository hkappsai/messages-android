package co.hkapps.messages;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Contact cards: send a contact as a vCard or as plain text, and read cards people send. */
final class VCard {

    static final class Card {
        String name;
        final List<String> phones = new ArrayList<>();
        final List<String> emails = new ArrayList<>();

        String summary() {
            StringBuilder sb = new StringBuilder(name == null ? "Contact" : name);
            for (String p : phones) sb.append('\n').append(p);
            for (String e : emails) sb.append('\n').append(e);
            return sb.toString();
        }
    }

    private VCard() {}

    /** The .vcf data for a picked contact (content://com.android.contacts/contacts/…). */
    static byte[] fromContact(Context c, Uri contact) {
        String lookup = null;
        try (Cursor cur = c.getContentResolver().query(contact,
                new String[]{ContactsContract.Contacts.LOOKUP_KEY}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) lookup = cur.getString(0);
        } catch (RuntimeException ignored) {
            // Fall through.
        }
        if (lookup == null) return null;
        Uri vcard = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookup);
        try (InputStream in = c.getContentResolver().openInputStream(vcard)) {
            return in == null ? null : Mms.readAll(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** "Send as text": name, numbers and emails as plain lines. */
    static String asText(Context c, Uri contact) {
        Card card = new Card();
        String id = contact.getLastPathSegment();
        try (Cursor cur = c.getContentResolver().query(contact,
                new String[]{ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts._ID}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                card.name = cur.getString(0);
                id = cur.getString(1);
            }
        } catch (RuntimeException ignored) {
            // Fall through with what we have.
        }
        try (Cursor cur = c.getContentResolver().query(Phone.CONTENT_URI, new String[]{Phone.NUMBER},
                Phone.CONTACT_ID + " = ?", new String[]{id}, null)) {
            while (cur != null && cur.moveToNext()) card.phones.add(cur.getString(0));
        } catch (RuntimeException ignored) {
            // No numbers.
        }
        try (Cursor cur = c.getContentResolver().query(Email.CONTENT_URI, new String[]{Email.ADDRESS},
                Email.CONTACT_ID + " = ?", new String[]{id}, null)) {
            while (cur != null && cur.moveToNext()) card.emails.add(cur.getString(0));
        } catch (RuntimeException ignored) {
            // No emails.
        }
        return card.summary();
    }

    /** Reads the first card in .vcf data. Unfolds lines and ignores fields we don't show. */
    static Card parse(byte[] data) {
        Card card = new Card();
        String text = new String(data, StandardCharsets.UTF_8).replace("\r\n ", "").replace("\n ", "");
        for (String line : text.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).toUpperCase();
            String value = line.substring(colon + 1).trim();
            if (key.equals("FN") || key.startsWith("FN;")) card.name = value;
            else if ((key.equals("N") || key.startsWith("N;")) && card.name == null) {
                String[] p = value.split(";");
                card.name = ((p.length > 1 ? p[1] + " " : "") + p[0]).trim();
            } else if (key.startsWith("TEL")) card.phones.add(value);
            else if (key.startsWith("EMAIL")) card.emails.add(value);
            else if (key.equals("END") && value.equalsIgnoreCase("VCARD")) break;
        }
        return card;
    }

    /** Opens the contacts app with the card filled in, ready to save. */
    static Intent addContactIntent(Card card) {
        Intent i = new Intent(ContactsContract.Intents.Insert.ACTION)
                .setType(ContactsContract.RawContacts.CONTENT_TYPE);
        if (card.name != null) i.putExtra(ContactsContract.Intents.Insert.NAME, card.name);
        if (!card.phones.isEmpty()) i.putExtra(ContactsContract.Intents.Insert.PHONE, card.phones.get(0));
        if (card.phones.size() > 1) i.putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, card.phones.get(1));
        if (!card.emails.isEmpty()) i.putExtra(ContactsContract.Intents.Insert.EMAIL, card.emails.get(0));
        return i;
    }

    /** Opens the contacts app to add a number we only know from a chat. */
    static Intent addNumberIntent(String number) {
        return new Intent(ContactsContract.Intents.Insert.ACTION)
                .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, number);
    }
}
