package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.network.standalone.ReleaseName
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNameTest {
    @Test fun `uncertain title words years bracket content and initials are preserved`() {
        listOf(
            "The Complete Stories", "A Journey (Volume II)", "Paris 1919", "History of US Steel",
            "FRENCH LESSONS", "ENGLISH PATIENT", "The Audible Past", "The Unabridged Life",
            "A Novel [1984]", "A Novel (A Second Story)", "A Novel {edition}",
            "A Novel [MP3 Adventures]", "D'après une histoire vraie",
            "A Novel \uE00042\uE001 (Volume II)",
        ).forEach { title ->
            assertEquals(title, ReleaseName(title, null), ReleaseName.parse(title))
            assertEquals(title, ReleaseName(title, null), ReleaseName.parse("$title [MP3]"))
        }
    }

    @Test fun `only wholly recognized technical groups are removed`() {
        listOf("[MP3 128kbps]", "[MP3-128]", "[Fr.mp3.160]", "[mp3 à 128 kb/s]", "[ENG]", "[AAX]", "(retail)").forEach { tag ->
            assertEquals(tag, ReleaseName("A Journey", null), ReleaseName.parse("A Journey $tag"))
        }
        assertEquals("Digital Minimalism (Volume II)", ReleaseName.parse("Digital Minimalism (Volume II) [CBZ]", comic = true).title)
    }

    @Test fun `release cleanup retains unverified names dates and edition text`() {
        val cases = listOf(
            "Harry Potter JK Rowling -NOTAG" to ReleaseName("Harry Potter JK Rowling", null),
            "Harry.Potter.J.K.Rowling-NOTAG" to ReleaseName("Harry Potter J K Rowling", null),
            "J.K.Rowling - Harry Potter à l’école des sorciers [MP3]" to ReleaseName("J K Rowling - Harry Potter à l’école des sorciers", null),
            "harry potter intégrale - 7 tomes en" to ReleaseName("harry potter intégrale - 7 tomes", null),
            "Harry Potter intégrale - tomes 1 à 7 en" to ReleaseName("Harry Potter intégrale - tomes 1 à 7", null),
            "Harry Potter Tome 7 - J.K. Rowling" to ReleaseName("Harry Potter Tome 7 - J K Rowling", null),
            "The English Patient [MP3]" to ReleaseName("The English Patient", null),
            "The French Revolution - An Author [MP3]" to ReleaseName("The French Revolution - An Author", null),
            "Digital Minimalism - Cal Newport" to ReleaseName("Digital Minimalism - Cal Newport", null),
            "Code Ardant Marge Nantel FR -PiXeL" to ReleaseName("Code Ardant Marge Nantel", null),
            "Code Ardant Marge Nantel [MP3]-DifferentGroup" to ReleaseName("Code Ardant Marge Nantel", null),
            "Impact Winter Tome 2 French EditionTravis Beacham [MP3]" to ReleaseName("Impact Winter Tome 2 French EditionTravis Beacham", null),
            "Twenty-One Days [MP3]" to ReleaseName("Twenty-One Days", null),
            "Série Tome 1.5 [M4B]" to ReleaseName("Série Tome 1.5", null),
            "1984" to ReleaseName("1984", null),
            "1984 Une fiction sonore originale George Orwell 2024" to ReleaseName("1984 Une fiction sonore originale George Orwell 2024", null),
            "1984.Une.fiction.sonore.originale.George.Orwell.2024.FR.[MP3.96.kbps]-Notag" to ReleaseName("1984 Une fiction sonore originale George Orwell 2024", null),
            "Dune Une fiction sonore originale d’après l’œuvre de Frank Herbert 2023" to ReleaseName("Dune Une fiction sonore originale d’après l’œuvre de Frank Herbert 2023", null),
            "Example [MP3] (retail) {edition}" to ReleaseName("Example {edition}", null),
            "Example.FR.MP3" to ReleaseName("Example", null),
            "Example_EN-M4B" to ReleaseName("Example", null),
            "Kurt.Wallander.09.La.Muraille.Invisible.Henning.Mankell.2014.FR.[MP3.128.kpbs]-SPL26" to ReleaseName("Kurt Wallander 09 La Muraille Invisible Henning Mankell 2014", null),
            "Henning.Mankell.La.muraille.invisible.Wallander.9.2014.FR.CD.MP3.128KBPS.Audiobook-Keb" to ReleaseName("Henning Mankell La muraille invisible Wallander 9 2014 FR Audiobook", null),
            "Rebecca.Yarros.Onyx.Storm.2025.FR.MP3.128kbps-NoTAG" to ReleaseName("Rebecca Yarros Onyx Storm 2025", null),
            "George Orwell - Animal Farm and 1984 performed by Stephen Fry [English] [2021] [mp3-64Kbps]" to ReleaseName("George Orwell - Animal Farm and 1984", null),
            "Adaptation d'après Mary Shelley - Frankenstein [2005] [mp3-128Kbps]" to ReleaseName("Adaptation d'après Mary Shelley - Frankenstein [2005]", null),
            "D'après Daniel Defoe - Robinson Crusoé raconté par Claude Rich [mp3 160kbps]" to ReleaseName("D'après Daniel Defoe - Robinson Crusoé", null),
            "Freida.McFadden.Chere.Debbie.Mp3.128.Kbps.Audiobook.2026.FRENCH.RETAiL.eBOOK-NoTag" to ReleaseName("Freida McFadden Chere Debbie Audiobook 2026 FRENCH RETAiL", null),
            "Sarah.J.Maas.Un.palais.d'épines.et.de.roses.2.Un.palais.de.colère.et.de.brume.2023.FR.mp3-64-NoTag" to ReleaseName("Sarah J Maas Un palais d'épines et de roses 2 Un palais de colère et de brume 2023", null),
        )
        cases.forEach { (raw, expected) -> assertEquals(raw, expected, ReleaseName.parse(raw)) }
        assertEquals("Comic v2 c14 digital", ReleaseName.parse("Comic v2 c14 digital [CBZ]", comic = true).title)
        assertEquals("Harry Potter JK Rowling", ReleaseName.parse("Harry Potter JK Rowling -NOTAG").query)
        assertEquals("Suis-je hypersensible ? Fabrice Midal", ReleaseName.parse("Suis-je hyper sensible ? - Fabrice Midal").query)
    }
}
