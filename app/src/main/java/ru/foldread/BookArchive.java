package ru.foldread;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.zhanghai.android.libarchive.Archive;
import me.zhanghai.android.libarchive.ArchiveEntry;
import me.zhanghai.android.libarchive.ArchiveException;

/** Reads one selected member into an app-owned temporary file, never using its path on disk. */
final class BookArchive implements AutoCloseable {
    static final int MAX_CONTAINER = 512 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;
    private final long archive;

    static final class Member {
        final int index;
        final String path;
        Member(int index, String path) { this.index = index; this.path = path; }
    }

    private BookArchive(File file) throws IOException {
        archive = Archive.readNew();
        try {
            Archive.setCharset(archive, "UTF-8".getBytes(StandardCharsets.US_ASCII));
            Archive.readSupportFormatZip(archive);
            Archive.readSupportFormat7zip(archive);
            Archive.readSupportFormatRar(archive);
            Archive.readSupportFormatRar5(archive);
            Archive.readOpenFileName(archive, file.getPath().getBytes(StandardCharsets.UTF_8), 65536);
        } catch (ArchiveException ex) {
            Archive.free(archive);
            throw ex;
        }
    }

    // A null result denotes a standalone book. EPUB is itself a ZIP with an OCF container.
    static List<Member> books(File file) throws IOException {
        byte[] header = new byte[8];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(header) < 4) return null;
        }
        boolean zip = header[0] == 'P' && header[1] == 'K';
        boolean rar = header[0] == 'R' && header[1] == 'a' && header[2] == 'r' && header[3] == '!';
        boolean seven = header[0] == '7' && header[1] == 'z' && header[2] == (byte) 0xbc;
        if (!zip && !rar && !seven) return null;
        List<Member> books = new ArrayList<>();
        try (BookArchive reader = new BookArchive(file)) {
            long entry;
            for (int index = 0; (entry = Archive.readNextHeader(reader.archive)) != 0; index++) {
                if (index >= MAX_ENTRIES) throw new IOException("В архиве больше 100 000 файлов.");
                if (ArchiveEntry.filetype(entry) != ArchiveEntry.AE_IFREG) continue;
                String path = entryName(entry);
                if (zip && path.equals("META-INF/container.xml")) return null;
                String lower = path.toLowerCase(Locale.ROOT);
                String leaf = path.substring(path.lastIndexOf('/') + 1);
                if ((lower.endsWith(".fb2") || lower.endsWith(".epub"))
                        && !leaf.startsWith("._") && !path.startsWith("__MACOSX/"))
                    books.add(new Member(index, path));
            }
        }
        if (books.isEmpty()) throw new IOException("В архиве нет файлов FB2 или EPUB.");
        return books;
    }

    private static String entryName(long entry) {
        String name = ArchiveEntry.pathnameUtf8(entry);
        if (name == null) {
            byte[] raw = ArchiveEntry.pathname(entry);
            if (raw == null) return "";
            name = new String(raw, StandardCharsets.UTF_8);
            if (name.indexOf('\uFFFD') >= 0) name = new String(raw, Charset.forName("IBM866"));
        }
        return name.replace('\\', '/');
    }

    static void extract(File file, Member selected, File output) throws IOException {
        try (BookArchive reader = new BookArchive(file)) {
            long entry;
            for (int index = 0; (entry = Archive.readNextHeader(reader.archive)) != 0; index++) {
                if (index != selected.index) continue;
                if (ArchiveEntry.isEncrypted(entry)) throw new IOException("Для книги нужен архив без пароля.");
                long total = 0;
                if (ArchiveEntry.size(entry) > BookImporter.MAX_FILE) throw tooLarge();
                ByteBuffer bytes = ByteBuffer.allocate(65536);
                try (FileOutputStream out = new FileOutputStream(output)) {
                    while (true) {
                        bytes.clear();
                        Archive.readData(reader.archive, bytes);
                        int count = bytes.position();
                        if (count == 0) break;
                        total += count;
                        if (total > BookImporter.MAX_FILE) throw tooLarge();
                        out.write(bytes.array(), 0, count);
                    }
                }
                if (ArchiveEntry.sizeIsSet(entry) && total != ArchiveEntry.size(entry))
                    throw new IOException("Книга в архиве повреждена.");
                return;
            }
            throw new IOException("Не удалось найти выбранную книгу.");
        }
    }

    private static IOException tooLarge() { return new IOException("Книга в архиве больше 64 МиБ."); }
    @Override public void close() throws IOException { Archive.free(archive); }
}
