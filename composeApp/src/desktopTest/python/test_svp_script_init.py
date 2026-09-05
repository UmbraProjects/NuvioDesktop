"""Run with the MSYS Python matching the bundled VapourSynth runtime."""
import ast
from pathlib import Path
import unittest
import vapoursynth as vs

SCRIPT = Path(__file__).resolve().parents[2] / "desktopMain/resources/player-scripts/svp_main.vpy"
TREE = ast.parse(SCRIPT.read_text(encoding="utf-8"), filename=str(SCRIPT))


class SvpScriptInitializationTest(unittest.TestCase):
    def test_no_synchronous_frame_probe_in_any_pipeline(self):
        probes = [node for node in ast.walk(TREE) if isinstance(node, ast.Call)
                  and isinstance(node.func, ast.Attribute) and node.func.attr == "get_frame"]
        self.assertEqual([], probes, "mpv cannot serve real frames while the script is initializing")

    def check_conversion(self, kernel, full_range):
        core = vs.core
        requests = []
        clip = core.std.BlankClip(width=64, height=64, format=vs.PresetVideoFormat.YUV420P10,
                                  length=2, color=[0 if full_range else 64, 512, 512])
        if full_range:
            # Real frame properties must override the limited-range/709 fallback arguments.
            clip = core.std.SetFrameProps(clip, _ColorRange=0, _Matrix=6)
        else:
            clip = core.std.RemoveFrameProps(clip, props=["_ColorRange", "_Range", "_Matrix"])

        def record_request(n, f):
            requests.append(n)
            return f.copy()

        source = core.std.ModifyFrame(clip, clips=clip, selector=record_request)
        prepare = next(node for node in TREE.body
                       if isinstance(node, ast.FunctionDef) and node.name == "prepare_clip_svp")
        scope = {"core": core}
        exec(compile(ast.Module(body=[prepare], type_ignores=[]), str(SCRIPT), "exec"), scope)
        output = scope["prepare_clip_svp"](source, {"format": vs.PresetVideoFormat.YUV420P8, "resize_kernel": kernel})
        self.assertEqual([], requests, "graph construction requested a frame")
        with output.get_frame(0) as frame:
            self.assertTrue(requests)
            self.assertEqual(8, frame.format.bits_per_sample)
            self.assertEqual(1, frame.props["_ColorRange"])
            self.assertEqual(1, frame.props["_Matrix"])
            self.assertEqual(16, frame[0][0, 0], "black should convert to limited-range code 16")

    def test_bicubic_uses_real_frame_properties(self):
        self.check_conversion("Bicubic", True)

    def test_spline36_uses_real_frame_properties(self):
        self.check_conversion("Spline36", True)

    def test_bicubic_missing_properties_use_defaults(self):
        self.check_conversion("Bicubic", False)

    def test_spline36_missing_properties_use_defaults(self):
        self.check_conversion("Spline36", False)


if __name__ == "__main__":
    unittest.main()
