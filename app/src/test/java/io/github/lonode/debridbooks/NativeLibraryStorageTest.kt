package io.github.lonode.debridbooks

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.lonode.debridbooks.audio.*
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.MediaPart
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NativeLibraryStorageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val account = MobileContract.localId("synthetic_account")
    private val source = SearchItem("tbab:synthetic_private_tracker_passkey", "Book", author="Author")
    private val item = MobileContract.localId(source.id)
    private val part = MediaPart("Part", "https://example.invalid/temporary-private-media", "part.mp3")
    private val codec = object : SecretCodec {
        override fun protect(value: String, account: String) = "$account:$value".toByteArray()
        override fun reveal(bytes: ByteArray, account: String): String { val text=bytes.toString(Charsets.UTF_8); require(text.startsWith("$account:"));return text.removePrefix("$account:") }
    }
    private fun database() = Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build()
    @Test fun `v4 migration retains comic offline state resolver data and audio progress`() = runTest {
        val name="native-v5-migration.db";context.deleteDatabase(name)
        val path=context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path,null).use { sql ->
            sql.createLegacyLibrary(4)
            sql.execSQL("INSERT INTO books VALUES ('a','b','Audio',NULL,1)")
            sql.execSQL("INSERT INTO progress VALUES ('a','b','p',0,55000,1.25,0,1)")
            sql.execSQL("INSERT INTO audio_sources VALUES ('a','b',X'1234')")
            sql.execSQL("INSERT INTO comics VALUES ('a','v','i','Comic',NULL,X'5678',54,178,0,1,1,'COMPLETE',178,X'0102')")
            sql.version=4
        }
        val db=Room.databaseBuilder(context,LibraryDatabase::class.java,name).addMigrations(MIGRATION_4_5, MIGRATION_5_6).build()
        try {
            assertEquals(55000L,db.library().progress("a","b")!!.positionMs)
            assertArrayEquals(byteArrayOf(0x12,0x34),db.library().audioSource("a","b")!!.payload)
            val comic=db.comics().get("a","v")!!;assertTrue(comic.offline);assertEquals(54,comic.page);assertEquals("COMPLETE",comic.downloadState)
            db.audio().updatePersonal("a","b"){it.copy(saved=true)};assertTrue(db.audio().personal("a","b").extras().saved)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun `download lifecycle updates cannot revive complete paused removed or foreign-account jobs`() = runTest {
        val db=database()
        try {
            val book=BookEntity(account,item,"Book",null,1);db.library().upsertBook(book)
            val dao=db.audio();dao.enqueue(AudioDownload(account,item));dao.enqueue(AudioDownload(account,item))
            assertEquals(1,dao.pending().size);assertEquals(1,dao.tryStart(account,item,2));assertEquals(0,dao.tryStart(account,item,3))
            dao.state(account,item,"PAUSED","Paused");dao.downloadProgress(account,item,999,null)
            assertEquals(0L,dao.download(account,item)!!.bytes)
            dao.enqueue(AudioDownload(account,item));dao.tryStart(account,item,4)
            dao.putDownload(AudioDownload(account,item,state="COMPLETE",bytes=100,total=100));dao.downloadProgress(account,item,1,null);dao.state(account,item,"FAILED","late failure")
            assertEquals("COMPLETE",dao.download(account,item)!!.state);assertEquals(100L,dao.download(account,item)!!.bytes)
            assertNull(dao.download("other",item));db.library().removeBook(account,item);assertNull(dao.download(account,item))
        }finally{db.close()}
    }
    @Test fun `offline playback validates every file and excludes network URLs from durable manifests`() = runTest {
        val db=database();val repo=AudioRepository(context,db,codec)
        try {
            repo.remember(account,source)
            val directory=File(File(File(context.noBackupFilesDir,"audio-books"),MobileContract.localId(account)),MobileContract.localId(item)).apply{mkdirs()}
            File(directory,"${part.id}.audio").writeBytes(byteArrayOf(1,2,3))
            val manifest=OfflineAudio(listOf(OfflinePart(part.id,part.title,part.filename,3)))
            val text=libraryJson.encodeToString(manifest);assertFalse(text.contains(part.url))
            db.audio().putDownload(AudioDownload(account,item,state="COMPLETE",manifest=codec.protect(text,account)))
            val offline=repo.offline(account,item)!!;assertTrue(offline.ready);assertTrue(offline.streams.single().url.startsWith("file:"));assertEquals(part.id,offline.streams.single().id)
            File(directory,"${part.id}.audio").writeBytes(byteArrayOf(1))
            assertNull(repo.offline(account,item));assertEquals("FAILED",db.audio().download(account,item)!!.state)
        }finally{repo.delete(account,item);db.close()}
    }
    @Test fun `encrypted backup preserves sensitive identities without exposing them and repeated merge is idempotent`() = runTest {
        val db=database();val job=Job()
        val file=File(context.cacheDir,"native-backup-${System.nanoTime()}.preferences_pb")
        val preferences=PreferencesRepository(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
        val backup=LibraryBackup(db,codec,preferences);val password="synthetic backup passphrase".toCharArray()
        try {
            val book=BookEntity(account,item,"Book","Author",1)
            db.library().rememberSource(book,AudioSource(account,item,codec.protect(libraryJson.encodeToString(source),account)))
            db.library().checkpoint(book,ProgressEntity(account,item,part.id,0,1000,1f,false,10))
            db.audio().updatePersonal(account,item){it.copy(saved=true,bookmarks=listOf(AudioBookmark("mark",part.id,1000,"Part","Note",10)))}
            db.audio().addListening(account,item,"2026-10-25",1000)
            val bytes=backup.export(password)
            assertFalse(bytes.toString(Charsets.UTF_8).contains(source.id));assertFalse(bytes.toString(Charsets.UTF_8).contains(part.url))
            assertTrue(runCatching{backup.preview(bytes,"incorrect passphrase".toCharArray())}.isFailure)
            val preview=backup.preview(bytes,password);assertEquals(1,preview.books);assertEquals(1,preview.bookmarks)
            db.library().checkpoint(book,ProgressEntity(account,item,part.id,0,10000,1.5f,false,20))
            db.audio().addListening(account,item,"2026-10-25",1000)
            assertTrue(backup.merge(preview));assertTrue(backup.merge(preview))
            assertEquals(10000L,db.library().progress(account,item)!!.positionMs)
            assertEquals(2000L,db.audio().listening(account,item,"2026-10-25")!!.milliseconds)
            assertEquals(1,db.audio().personal(account,item).extras().bookmarks.size)
            assertEquals(source.id,libraryJson.decodeFromString<SearchItem>(codec.reveal(db.library().audioSource(account,item)!!.payload,account)).id)
            val tampered=bytes.copyOf().also{it[it.size-20]=(it[it.size-20].toInt() xor 1).toByte()}
            assertTrue(runCatching{backup.preview(tampered,password)}.isFailure)
        }finally{password.fill('\u0000');db.close();job.cancelAndJoin();file.delete()}
    }
    @Test fun `new progress samples do not invalidate presentation metadata versions`() = runTest {
        val db=database()
        try {
            val book=BookEntity(account,item,"Book",null,1)
            db.library().checkpoint(book,ProgressEntity(account,item,part.id,0,1000,1f,false,1))
            db.library().checkpoint(book.copy(addedAt=999),ProgressEntity(account,item,part.id,0,2000,1f,false,2))
            assertEquals(1L,db.library().books(account).single().addedAt)
        }finally{db.close()}
    }
    @Test fun `failed audio download reuses verified completed parts on retry without storing media URLs`() = runTest {
        val db=database(); val repo=AudioRepository(context,db,codec)
        val server=java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        val base="http://127.0.0.1:${server.localPort}"
        val failSecond=java.util.concurrent.atomic.AtomicBoolean(true)
        val firstReads=java.util.concurrent.atomic.AtomicInteger()
        val sentAuth=java.util.concurrent.atomic.AtomicBoolean()
        val worker=kotlin.concurrent.thread(isDaemon=true) {
            while(!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout=5000
                    val input=socket.getInputStream().bufferedReader()
                    val path=input.readLine().split(" ")[1]
                    var header=input.readLine()
                    while(!header.isNullOrEmpty()){ if(header.startsWith("Authorization:",true))sentAuth.set(true);header=input.readLine() }
                    val status=if(path=="/two" && failSecond.get())503 else 200
                    val body=when {
                        path.contains("/streams/") -> """{"ready":true,"streams":[{"title":"One","filename":"one.mp3","url":"$base/one"},{"title":"Two","filename":"two.mp3","url":"$base/two"}]}"""
                        path=="/one" -> {firstReads.incrementAndGet();"synthetic-audio-one"}
                        path=="/two" && status==200 -> "synthetic-audio-two"
                        else -> "unavailable"
                    }.toByteArray()
                    socket.getOutputStream().apply {write("HTTP/1.1 $status Test\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
                }
            }catch(_:java.io.IOException){}
        }
        try {
            val connection=io.github.lonode.debridbooks.network.Connection("$base/synthetic",account)
            repo.queue(account,source)
            db.audio().tryStart(account,item,1)
            assertTrue(runCatching{repo.download(connection,db.audio().download(account,item)!!)}.isFailure)
            assertNotNull(db.audio().download(account,item)!!.manifest)
            db.audio().state(account,item,"FAILED","Synthetic interruption")
            assertNull(repo.offline(account,item))
            failSecond.set(false)
            repo.queue(account,source); db.audio().tryStart(account,item,2)
            repo.download(connection,db.audio().download(account,item)!!)
            assertEquals(1,firstReads.get())
            assertEquals("COMPLETE",db.audio().download(account,item)!!.state)
            val offline=repo.offline(account,item)!!;assertEquals(2,offline.streams.size)
            assertEquals(listOf("One","Two"),offline.streams.map{it.title})
            val manifest=codec.reveal(db.audio().download(account,item)!!.manifest!!,account)
            assertFalse(manifest.contains(base));assertFalse(sentAuth.get())
        }finally{repo.delete(account,item);db.close();server.close();worker.join(5000)}
    }
    @Test fun `corrupt personal metadata is preserved when a write fails and old process jobs pause safely`() = runTest {
        val db=database()
        try {
            db.library().upsertBook(BookEntity(account,item,"Book",null,1))
            db.audio().putPersonal(AudioPersonal(account,item,"{invalid",1))
            assertTrue(runCatching{db.audio().updatePersonal(account,item){it.copy(saved=true)}}.isFailure)
            assertEquals("{invalid",db.audio().personal(account,item)!!.json)
            db.audio().putDownload(AudioDownload(account,item,state="RUNNING",owner="old-process"))
            db.audio().reconcileInterrupted("new-process")
            assertEquals("PAUSED",db.audio().download(account,item)!!.state)
            db.audio().putDownload(AudioDownload(account,item,state="RUNNING",owner="new-process"))
            db.audio().reconcileInterrupted("new-process")
            assertEquals("RUNNING",db.audio().download(account,item)!!.state)
        }finally{db.close()}
    }

    @Test fun `comic backup restores reading history without inventing downloads and preserves newer offline history`() = runTest {
        val db=database(); val job=Job()
        val file=File(context.cacheDir,"comic-backup-${System.nanoTime()}.preferences_pb")
        val preferences=PreferencesRepository(PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),produceFile={file}))
        val backup=LibraryBackup(db,codec,preferences)
        val comicSource=SearchItem("tbab:synthetic_comic_private", "Comic", type="comic")
        val comicId=MobileContract.localId(comicSource.id); val volume=MobileContract.localId("synthetic-volume")
        val password="synthetic comic backup".toCharArray()
        try {
            val entry=ComicEntity(account,volume,comicId,"Comic",null,codec.protect(comicSource.id,account),54,178,false,true,20,"COMPLETE",178,codec.protect(libraryJson.encodeToString(comicSource),account))
            db.comics().save(entry)
            val preview=backup.preview(backup.export(password),password); assertEquals(1,preview.comics)
            db.comics().remove(account,volume); assertTrue(backup.merge(preview))
            val restored=db.comics().get(account,volume)!!
            assertEquals(54,restored.page); assertFalse(restored.offline); assertEquals("NONE",restored.downloadState)
            assertEquals(comicSource.id,codec.reveal(restored.resolver,account))
            db.comics().save(entry.copy(page=10,updatedAt=1)); backup.merge(preview)
            assertEquals(54,db.comics().get(account,volume)!!.page); assertTrue(db.comics().get(account,volume)!!.offline)
            db.comics().save(entry.copy(page=70,updatedAt=30)); backup.merge(preview)
            assertEquals(70,db.comics().get(account,volume)!!.page); assertTrue(db.comics().get(account,volume)!!.offline)
        } finally { password.fill('\u0000'); db.close(); job.cancelAndJoin(); file.delete() }
    }
    @Test fun `remembering an uncached listening selection persists its source without progress or downloads and keeps a saved place on retry`() = runTest {
        val db=database();val repo=AudioRepository(context,db,codec)
        try {
            val uncached=source.copy(cached=false)
            repo.remember(account,uncached)
            assertEquals(uncached,repo.source(account,item))
            assertNotNull(db.library().getBook(account,item))
            assertNull(db.library().progress(account,item));assertNull(db.audio().download(account,item))
            val saved=ProgressEntity(account,item,"saved-part",2,123456,1.25f,false,100)
            db.library().checkpoint(db.library().getBook(account,item)!!,saved)
            repo.remember(account,uncached)
            assertEquals(saved,db.library().progress(account,item))
            assertNull(db.audio().download(account,item))
            assertNull(db.library().getBook("other",item))
        } finally { db.close() }
    }

}
