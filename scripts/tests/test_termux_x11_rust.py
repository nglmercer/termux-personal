#!/usr/bin/env python3
"""Host tests for the Termux:X11 Rust crate rewriter (no Cargo network access)."""
import importlib.machinery
import importlib.util
import pathlib
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[2] / "tools/termux-x11/termux-x11-rust"
loader = importlib.machinery.SourceFileLoader("termux_x11_rust", str(SCRIPT))
spec = importlib.util.spec_from_loader(loader.name, loader)
tool = importlib.util.module_from_spec(spec)
loader.exec_module(tool)


class RewriteTest(unittest.TestCase):
    def test_android_cfgs_select_linux_backends(self):
        source = '#[cfg(target_os = "android")]\nmod android;\n#[cfg(any(target_os="linux", target_os = "freebsd"))]\nmod linux;\n'
        self.assertEqual(
            tool.rewrite_cfgs(source),
            '#[cfg(target_os = "termux_android_app")]\nmod android;\n'
            '#[cfg(any(any(target_os = "linux", target_os = "android"), target_os = "freebsd"))]\nmod linux;\n')

    def test_cargo_target_tables_are_rewritten(self):
        manifest = (
            "[target.'cfg(target_os = \"android\")'.dependencies.ndk]\nversion = \"0.9\"\n"
            "[target.'cfg(all(unix, not(any(target_os = \"android\", target_os = \"macos\"))))'.dependencies.x11-dl]\n")
        rewritten = tool.rewrite_cfgs(manifest)
        self.assertIn("cfg(target_os = \"termux_android_app\")'.dependencies.ndk", rewritten)
        self.assertIn("not(any(target_os = \"termux_android_app\", target_os = \"macos\"))", rewritten)

    def test_build_script_target_string_checks(self):
        script = 'let android = target.contains("android");\nif target_os == "android" {}\n'
        self.assertEqual(
            tool.rewrite_build_script(script),
            'let android = false;\nif target_os == "termux_android_app" {}\n')

    def test_rewrite_is_idempotent(self):
        once = tool.rewrite_cfgs('cfg(target_os = "linux")')
        self.assertEqual(tool.rewrite_cfgs(tool.rewrite_cfgs('cfg(target_os = "android")')), 'cfg(target_os = "termux_android_app")')
        self.assertNotEqual(tool.rewrite_cfgs(once), once, "linux rewrite must only run on pristine sources")


class CrateCopyTest(unittest.TestCase):
    def test_patch_crate_marks_copy_and_only_touches_sources(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            source = root / "registry/demo-1.0.0"
            (source / "src").mkdir(parents=True)
            (source / "build.rs").write_text('fn main() { let android = target.contains("android"); }\n')
            (source / "src/lib.rs").write_text('#[cfg(target_os = "linux")] pub fn x() {}\n')
            (source / "Cargo.toml.orig").write_text('cfg(target_os = "android")\n')
            (source / "README.md").write_text('target_os = "android"\n')
            destination = root / "project/.termux-x11/crates/demo-1.0.0"
            self.assertEqual(tool.patch_crate(source, destination), 2)
            self.assertTrue((destination / ".termux-x11-patched").exists())
            self.assertIn("let android = false", (destination / "build.rs").read_text())
            self.assertIn('target_os = "android"', (destination / "README.md").read_text())
            self.assertIn('target_os = "android"', (destination / "Cargo.toml.orig").read_text())
            self.assertEqual(tool.unpatched_source(None, "demo", "1.0.0", source), source)


class ConfigTest(unittest.TestCase):
    def test_managed_block_preserves_user_config_and_is_removable(self):
        with tempfile.TemporaryDirectory() as temp:
            project = pathlib.Path(temp)
            config = project / ".cargo/config.toml"
            config.parent.mkdir()
            config.write_text("[build]\njobs = 2\n")
            entries = [("tao", "0.37.3", project / "a"), ("winit", "0.29.0", project / "b"), ("winit", "0.30.13", project / "c")]
            tool.write_config(project, tool.render_patch_block(entries))
            text = config.read_text()
            self.assertTrue(text.startswith("[build]\njobs = 2\n\n" + tool.BEGIN))
            self.assertIn('winit-0_30_13 = { path = "%s", package = "winit" }' % (project / "c").as_posix(), text)
            tool.write_config(project, tool.render_patch_block(entries[:1]))
            self.assertEqual(config.read_text().count(tool.BEGIN), 1)
            tool.write_config(project, "")
            self.assertEqual(config.read_text(), "[build]\njobs = 2\n")

    def test_legacy_extensionless_config_wins(self):
        with tempfile.TemporaryDirectory() as temp:
            project = pathlib.Path(temp)
            (project / ".cargo").mkdir()
            legacy = project / ".cargo/config"
            legacy.write_text("[build]\njobs = 2\n")
            entries = [("tao", "0.37.3", project / "a")]
            tool.write_config(project, tool.render_patch_block(entries))
            self.assertIn(tool.BEGIN, legacy.read_text())
            self.assertFalse((project / ".cargo/config.toml").exists())
            tool.write_config(project, "")
            self.assertEqual(legacy.read_text(), "[build]\njobs = 2\n")

    def test_cpal_hook_enables_pulseaudio_default(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            source = root / "registry/cpal-0.18.2"
            source.mkdir(parents=True)
            (source / "Cargo.toml").write_text("[features]\ndefault = []\npulseaudio = []\n")
            destination = root / "project/.termux-x11/crates/cpal-0.18.2"
            tool.patch_crate(source, destination)
            self.assertIn('default = ["pulseaudio"]', (destination / "Cargo.toml").read_text())

    def test_refuses_to_merge_unmanaged_patch_table(self):
        with tempfile.TemporaryDirectory() as temp:
            project = pathlib.Path(temp)
            (project / ".cargo").mkdir()
            (project / ".cargo/config.toml").write_text("[patch.crates-io]\nfoo = { path = \"x\" }\n")
            with self.assertRaises(SystemExit):
                tool.write_config(project, tool.render_patch_block([("tao", "1", project)]))


if __name__ == "__main__":
    unittest.main()
