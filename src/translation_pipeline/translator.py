from __future__ import annotations

import logging
import time

from .config import TranslatorConfig

logger = logging.getLogger(__name__)


class Translator:
    """English → Spanish translation using argos-translate (offline).

    Downloads the language model on first use if not already installed.
    """

    def __init__(self, config: TranslatorConfig = TranslatorConfig()) -> None:
        self._config = config
        self._translate_fn = None

    def load_model(self) -> None:
        if self._translate_fn is not None:
            return

        import argostranslate.package
        import argostranslate.translate

        logger.info(
            "Loading Argos translation model %s → %s...",
            self._config.source_lang,
            self._config.target_lang,
        )

        argostranslate.package.update_package_index()
        available = argostranslate.package.get_available_packages()
        pkg = next(
            (
                p
                for p in available
                if p.from_code == self._config.source_lang
                and p.to_code == self._config.target_lang
            ),
            None,
        )

        if pkg is None:
            raise RuntimeError(
                f"No Argos package found for {self._config.source_lang} → {self._config.target_lang}"
            )

        installed_languages = argostranslate.translate.get_installed_languages()
        source_installed = any(
            lang.code == self._config.source_lang for lang in installed_languages
        )
        target_installed = any(
            lang.code == self._config.target_lang for lang in installed_languages
        )

        if not (source_installed and target_installed):
            logger.info("Downloading Argos language package (one-time)...")
            argostranslate.package.install_from_path(pkg.download())
            logger.info("Argos language package installed")

        installed_languages = argostranslate.translate.get_installed_languages()
        source_lang = next(
            lang for lang in installed_languages if lang.code == self._config.source_lang
        )
        target_lang = next(
            lang for lang in installed_languages if lang.code == self._config.target_lang
        )

        translation = source_lang.get_translation(target_lang)
        if translation is None:
            raise RuntimeError(
                f"Failed to load translation {self._config.source_lang} → {self._config.target_lang}"
            )

        self._translate_fn = translation.translate
        logger.info("Argos translation model loaded")

    def translate(self, text: str) -> str:
        """Translate text from source language to target language.

        Args:
            text: Text in source language.

        Returns:
            Translated text in target language.
        """
        if not text or not text.strip():
            return ""

        if self._translate_fn is None:
            self.load_model()
        assert self._translate_fn is not None

        t0 = time.monotonic()
        result = self._translate_fn(text)
        elapsed_ms = (time.monotonic() - t0) * 1000

        logger.debug("Translate: '%s' → '%s' (%.0fms)", text, result, elapsed_ms)
        return result
