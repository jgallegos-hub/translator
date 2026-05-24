from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from translation_pipeline.config import TranslatorConfig
from translation_pipeline.translator import Translator


class TestTranslator:
    def test_translate_returns_text(self):
        t = Translator(TranslatorConfig())
        t._translate_fn = lambda text: "Hola mundo"

        result = t.translate("Hello world")
        assert result == "Hola mundo"

    def test_translate_empty_string(self):
        t = Translator(TranslatorConfig())
        t._translate_fn = lambda text: "algo"

        assert t.translate("") == ""
        assert t.translate("   ") == ""

    def test_translate_preserves_content(self):
        captured = []

        def mock_translate(text):
            captured.append(text)
            return f"translated: {text}"

        t = Translator(TranslatorConfig())
        t._translate_fn = mock_translate

        result = t.translate("How are you?")
        assert result == "translated: How are you?"
        assert captured == ["How are you?"]

    def test_translate_multiple_calls(self):
        call_count = 0

        def mock_translate(text):
            nonlocal call_count
            call_count += 1
            return f"result_{call_count}"

        t = Translator(TranslatorConfig())
        t._translate_fn = mock_translate

        assert t.translate("one") == "result_1"
        assert t.translate("two") == "result_2"
        assert t.translate("three") == "result_3"


class TestTranslatorConfig:
    def test_defaults(self):
        c = TranslatorConfig()
        assert c.source_lang == "en"
        assert c.target_lang == "es"

    def test_custom(self):
        c = TranslatorConfig(source_lang="es", target_lang="en")
        assert c.source_lang == "es"
        assert c.target_lang == "en"
