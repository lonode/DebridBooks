"""Offline checks for the manual benchmark's sanitization and feed handling."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("capture", Path(__file__).with_name("book_search_capture.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class CaptureTest(unittest.TestCase):
    def test_private_source_fields_never_enter_capture(self):
        body = b'''<rss xmlns:t="http://torznab.com/schemas/2015/feed"><channel><item>
        <title>Example Writer - Example Book [MP3]</title>
        <enclosure url="https://tracker.invalid/download?passkey=synthetic-private"/>
        <t:attr name="infohash" value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"/>
        <t:attr name="language" value="fr"/><t:attr name="bookauthor" value="Example Writer"/>
        </item></channel></rss>'''
        self.assertEqual([{"releaseName": "Example Writer - Example Book [MP3]", "language": "fr", "bookauthor": "Example Writer"}], capture.parse(body))

    def test_json_feed_attributes_are_sanitized(self):
        body = b'''{"rss":{"channel":{"item":{"title":"Example Book","link":"https://private.invalid/key", "torznab:attr":{"@attributes":{"name":"author","value":"Example Writer"}}}}}}'''
        self.assertEqual([{"releaseName": "Example Book", "author": "Example Writer"}], capture.parse(body))

    def test_urls_in_unexpected_title_fields_are_rejected(self):
        for title in ("https://private.invalid/key", "magnet:?xt=synthetic", "Book passkey=synthetic"):
            self.assertIsNone(capture.release(title, {}))

    def test_utf8_text_matches_android_even_with_incorrect_xml_encoding_declaration(self):
        body = '<?xml version="1.0" encoding="ISO-8859-1"?><rss><channel><item><title>Éric - Félix</title></item></channel></rss>'.encode()
        self.assertEqual("Éric - Félix", capture.parse(body)[0]["releaseName"])

    def test_hostile_or_nonfeed_responses_are_not_successful_empty_feeds(self):
        for body in (b'<!DOCTYPE rss [<!ENTITY x "value">]><rss><channel/></rss>', b'<html>blocked</html>', b'<rss><channel><item>', b'{"error":{"code":100}}'):
            with self.assertRaises(Exception):
                capture.parse(body)


if __name__ == "__main__":
    unittest.main()
