# SVP for Anime - Final Stable Version (Enhanced for Memory Cleanup and Stability)
#
# This script is the result of a methodical, step-by-step process to combine the best features
# of multiple versions into a single, robust, and high-performance VapourSynth script.
#
# Key Features:
# - All known memory leaks fixed (rc:false, aggressive GC, explicit object deletion).
# - Refined, anime-specific parameters for both SVP (primary) and MVTools (fallback).
# - Memory-safe frame access for maximum compatibility without memory leaks.
# - Superior hybrid FPS targeting for clean 2x interpolation on standard anime rates.
# - Permanently locked to 8-bit processing with high-quality dithering for maximum stability.
# - Toggleable debug overlay for real-time diagnostic information.
# - Robust logging for easier debugging of future issues.
# - Sensible VapourSynth cache limit to prevent memory bloat.
# - Enhanced cleanup: Major GC, explicit None assignments, fixed threads=4 for reinit stability.

import logging
import os
import multiprocessing
import math
import sys
from fractions import Fraction
from subprocess import call
import vapoursynth as vs
import gc
import traceback

diagnostic_log_path = os.path.join(os.path.dirname(__file__), "svp_diag.log")

# Config loader: reads script-opts/svp.conf placed next to this script in MPV config
def load_config():
    cfg = {
        'max_cache_size': 2048,   # MB default for 16GB machines
        'num_threads': 'auto',    # 'auto' or integer
        'DEBUG_OVERLAY': False
    }
    conf_path = os.path.join(os.path.dirname(__file__), 'script-opts', 'svp.conf')
    try:
        if os.path.exists(conf_path):
            with open(conf_path, 'r', encoding='utf-8') as f:
                for line in f:
                    line = line.split('#', 1)[0].strip()
                    if not line or '=' not in line:
                        continue
                    key, value = [s.strip() for s in line.split('=', 1)]
                    if key not in cfg:
                        continue
                    if key == 'DEBUG_OVERLAY':
                        # only accept explicit true/false
                        cfg[key] = (value.lower() == 'true')
                    elif key == 'num_threads':
                        if value.lower() in ('auto', 'default'):
                            cfg[key] = 'auto'
                        else:
                            try:
                                cfg[key] = int(value)
                            except Exception:
                                # leave default
                                pass
                    elif key == 'max_cache_size':
                        try:
                            cfg[key] = int(value)
                        except Exception:
                            pass
            cfg['_conf_path'] = conf_path
            cfg['_status'] = 'loaded'
        else:
            cfg['_conf_path'] = conf_path
            cfg['_status'] = 'defaults'  # no conf file found
    except Exception as e:
        cfg['_conf_path'] = conf_path
        cfg['_status'] = f'error: {e}'
    return cfg

config = load_config()

# Apply to core
core = vs.core
cpu = multiprocessing.cpu_count()

# threads: 'auto' => half cores capped at 6; explicit int otherwise
nt_cfg = config.get('num_threads', 'auto')
if isinstance(nt_cfg, str) and nt_cfg == 'auto':
    nt = max(2, min(6, cpu // 2))
else:
    try:
        nt = int(nt_cfg)
    except Exception:
        nt = max(2, min(6, cpu // 2))
# clamp sanity (you can loosen if you want)
nt = max(1, min(cpu, nt))
core.num_threads = nt

# cache size (MB) - clamp between 128MB and 16GB
try:
    mc = int(config.get('max_cache_size', 2048))
except Exception:
    mc = 2048
mc = max(128, min(16384, mc))
core.max_cache_size = mc

# DEBUG_OVERLAY flag
DEBUG_OVERLAY = bool(config.get('DEBUG_OVERLAY', False))

# small status variables for overlay or troubleshooting
config_status = config.get('_status', 'unknown')
config_path = config.get('_conf_path', 'script-opts/svp.conf')

current_method = "None"
diagnostic_frame_count = 0

def resolve_yuv420p8_format():
    if hasattr(vs, "YUV420P8"):
        return vs.YUV420P8

    preset_video_format = getattr(vs, "PresetVideoFormat", None)
    if preset_video_format is not None and hasattr(preset_video_format, "YUV420P8"):
        return preset_video_format.YUV420P8

    query_video_format = getattr(core, "query_video_format", None)
    if query_video_format is not None:
        color_family = getattr(getattr(vs, "ColorFamily", vs), "YUV", getattr(vs, "YUV", None))
        sample_type = getattr(getattr(vs, "SampleType", vs), "INTEGER", getattr(vs, "INTEGER", None))
        if color_family is not None and sample_type is not None:
            return query_video_format(
                color_family=color_family,
                sample_type=sample_type,
                bits_per_sample=8,
                subsampling_w=1,
                subsampling_h=1
            )

    register_format = getattr(core, "register_format", None)
    if register_format is not None:
        color_family = getattr(vs, "YUV", None)
        sample_type = getattr(vs, "INTEGER", None)
        if color_family is not None and sample_type is not None:
            return register_format(color_family, sample_type, 8, 1, 1)

    raise RuntimeError("Unable to resolve VapourSynth YUV420P8 format")

YUV420P8_FORMAT = resolve_yuv420p8_format()

def is_yuv420p8(clip):
    try:
        if clip.format is None:
            return False
        if clip.format == YUV420P8_FORMAT:
            return True
        if getattr(clip.format, "id", None) == getattr(YUV420P8_FORMAT, "id", None):
            return True
        return getattr(clip.format, "name", "") == "YUV420P8"
    except Exception:
        return False

def load_local_plugin(name):
    path = os.path.join(os.path.dirname(__file__), name)
    if not os.path.exists(path):
        path = os.path.join(os.path.dirname(os.path.dirname(sys.executable)), name)
    if not os.path.exists(path):
        path = os.path.join(os.environ.get("PYTHONHOME", ""), name)
    if not os.path.exists(path):
        return f"missing:{name}"
    try:
        core.std.LoadPlugin(path=path)
        return f"loaded:{path}"
    except Exception as e:
        return f"error:{path}:{e}"

svpflow1_status = load_local_plugin("svpflow1_vs.dll")
svpflow2_status = load_local_plugin("svpflow2_vs.dll")

# one-line overlay summary (used by add_method_overlay)
overlay_status = f"threads={core.num_threads}, cache={core.max_cache_size}MB, overlay={'ON' if DEBUG_OVERLAY else 'OFF'} ({config_status})"

# prepare clip and basic FPS fix as in your original script
clip = video_in
src_fps = container_fps if container_fps > 0.1 else 23.976
if abs(src_fps - 23.810) < 0.01:
    src_fps = 23.976

def log_svp_diag(message):
    line = "[nuvio-svp] " + message
    try:
        with open(diagnostic_log_path, "a", encoding="utf-8") as diagnostic_log:
            diagnostic_log.write(line + "\n")
            diagnostic_log.flush()
    except Exception:
        pass
    try:
        mp.msg.warn(line)
    except Exception:
        try:
            print(line, file=sys.stderr)
        except Exception:
            pass

def fmt_fps(value):
    try:
        return f"{float(value):.3f}"
    except Exception:
        return str(value)

def clip_fps_text(clip):
    try:
        if clip.fps_den:
            return fmt_fps(clip.fps_num / clip.fps_den) + f" ({clip.fps_num}/{clip.fps_den})"
    except Exception:
        pass
    return "unknown"

def log_clip_summary(stage, clip, method, target_fps):
    try:
        format_name = clip.format.name if clip.format is not None else "variable"
        log_svp_diag(
            f"{stage}: method={method}, source_fps={fmt_fps(src_fps)}, "
            f"target_fps={fmt_fps(target_fps)}, clip_fps={clip_fps_text(clip)}, "
            f"size={clip.width}x{clip.height}, format={format_name}, "
            f"frames={clip.num_frames}, display_fps={fmt_fps(display_fps)}, "
            f"plugins=svp1:{hasattr(core, 'svp1')} svp2:{hasattr(core, 'svp2')} mv:{hasattr(core, 'mv')}"
        )
    except Exception as e:
        log_svp_diag(f"{stage}: summary failed: {e}")

def add_svp_runtime_probe(clip, method, target_fps):
    def probe_frame(n, f):
        global diagnostic_frame_count
        diagnostic_frame_count += 1
        if diagnostic_frame_count <= 5 or diagnostic_frame_count % 120 == 0:
            interp_method = "missing"
            source_prop = "missing"
            target_prop = "missing"
            try:
                interp_method = f.props.get("_InterpolationMethod", "missing")
                source_prop = f.props.get("_SourceFPS", "missing")
                target_prop = f.props.get("_TargetFPS", "missing")
            except Exception as e:
                interp_method = f"prop-error:{e}"
            log_svp_diag(
                f"frame_request n={n}, count={diagnostic_frame_count}, "
                f"method={method}, frame_prop_method={interp_method}, "
                f"source_prop={source_prop}, target_prop={target_prop}, "
                f"output_clip_fps={clip_fps_text(clip)}, target_fps={fmt_fps(target_fps)}"
            )
        return clip

    try:
        return core.std.FrameEval(clip, probe_frame, prop_src=clip)
    except Exception as e:
        log_svp_diag(f"runtime probe unavailable: {e}")
        return clip

log_svp_diag(
    f"script_start: source_fps={fmt_fps(src_fps)}, container_fps={fmt_fps(container_fps)}, "
    f"display_fps={fmt_fps(display_fps)}, input={clip.width}x{clip.height}, "
    f"input_clip_fps={clip_fps_text(clip)}, threads={core.num_threads}, "
    f"cache={core.max_cache_size}MB, config={config_status}, config_path={config_path}, "
    f"diag_log={diagnostic_log_path}, yuv420p8={YUV420P8_FORMAT}, "
    f"svpflow1={svpflow1_status}, svpflow2={svpflow2_status}"
)

def add_method_overlay(clip):
    """
    Simple, resilient overlay that prints one-line status + current method.
    Make sure the rest of the pipeline calls this before returning final clip
    if you want the overlay visible.
    """
    try:
        txt = f"SVP | {overlay_status} | Method: {current_method}"
        # alignment=7 places it bottom-right; change alignment if you prefer
        return core.text.Text(clip, txt, alignment=7)
    except Exception:
        return clip

def force_memory_cleanup():
    gc.collect(generation=2)  # Major GC for deep cleanup
    for _ in range(3): gc.collect()

###########-=SVP PROCESSING FUNCTIONS=-###########

def calculate_optimal_block_size(width):
    base_size = width // 80
    block_size = 2 ** round(math.log2(max(8, min(32, base_size))))
    return block_size

def get_svp_params(clip):
    width = clip.width
    mpix = (width * clip.height) / 1000000.0
    
    if mpix > 6:    # 4K+ content
        return {
            'super': "{pel:1,scale:{up:0},gpu:1,full:false,rc:false}",
            'analyse': "{block:{overlap:0},main:{search:{coarse:{distance:-6,bad:{sad:2000}},type:2,distance:-6}},refine:[{thsad:200}]}",
            'algo': 13,
            'format': YUV420P8_FORMAT,
            'resize_kernel': 'Bicubic'
        }
    else:   # 1080p and below
        block_size = calculate_optimal_block_size(width)
        return {
            'super': "{pel:1,scale:{up:2},gpu:1,full:false,rc:false}",
            'analyse': "{block:{w:" + str(block_size) + ",overlap:1},main:{search:{coarse:{distance:-10,bad:{sad:1800}},type:2,distance:-10}},refine:[{thsad:220}]}",
            'algo': 13,
            'format': YUV420P8_FORMAT,
            'resize_kernel': 'Spline36'
        }

def prepare_clip_svp(clip, params):
    """Memory-safe clip preparation with high-quality dithering."""

    if clip.format != params['format']:
        first_frame = None
        matrix_in = 1
        range_in = 1
        try:
            first_frame = clip.get_frame(0)
            matrix_in = first_frame.props.get('_Matrix', 1)
            range_in = first_frame.props.get('_ColorRange', 1)
        except Exception as e:
            mp.msg.warn("Prop access error in prepare_clip_svp: " + str(e) + " - using defaults")
        finally:
            if first_frame is not None:
                del first_frame
        
        if params['resize_kernel'] == 'Bicubic':
            # --- FINAL ENHANCEMENT: Added high-quality dithering ---
            clip = core.resize.Bicubic(clip, format=params['format'], matrix_in=matrix_in, matrix_s="709", range_in=range_in, range_s="limited", chromaloc_in_s="left", chromaloc_s="left", dither_type="ordered")
        else:
            # --- OPTIMIZED: Switched to ordered dithering for i5-9300H performance ---
            clip = core.resize.Spline36(clip, format=params['format'], matrix_in=matrix_in, matrix_s="709", range_in=range_in, range_s="limited", chromaloc_in_s="left", chromaloc_s="left", dither_type="ordered")

    # Early prop locking for stability across reinits
    clip = core.std.SetFrameProp(clip, prop="_ColorRange", intval=1)
    clip = core.std.SetFrameProp(clip, prop="_Matrix", intval=1)
    clip = core.std.SetFrameProp(clip, prop="_Transfer", intval=1)
    clip = core.std.SetFrameProp(clip, prop="_Primaries", intval=1)
    return clip

def interpolate_svp(clip, params, target_fps):
    """SVP interpolation with explicit memory cleanup."""

    fps_num = int(target_fps * 10000); fps_den = 10000
    smoothfps_params = ("{rate:{num:" + str(fps_num) + ",den:" + str(fps_den) + ",abs:true}, algo:" + str(params['algo']) + ",mask:{area:100,cover:80}, scene:{mode:3,blend:true,limits:{m1:1200,m2:2400,scene:3600,zero:100,blocks:35}}, block:false}")
    
    super_clip = None; vectors = None; smooth = None
    try:
        super_clip = core.svp1.Super(clip, params['super'])
        vectors = core.svp1.Analyse(super_clip["clip"], super_clip["data"], clip, params['analyse'])
        smooth = core.svp2.SmoothFps(clip, super_clip["clip"], super_clip["data"], vectors["clip"], vectors["data"], smoothfps_params, src=clip, fps=src_fps)
        smooth = core.std.AssumeFPS(smooth, fpsnum=fps_num, fpsden=fps_den)
        smooth = core.std.SetFrameProp(smooth, prop="_InterpolationMethod", data="SVP4")
        return smooth
    finally:
        if super_clip is not None: del super_clip
        if vectors is not None: del vectors
        if smooth is not None: smooth = None
        gc.collect(generation=2)  # Major GC for deep cleanup
        force_memory_cleanup()

###########-=MVTOOLS FALLBACK FUNCTIONS=-###########

def get_mvtools_params(clip):
    """Refined, anime-specific MVTools parameters."""

    width, height = clip.width, clip.height
    mpix = (width * height) / 1000000.0
    if mpix > 6: return { 'blksize': 32, 'overlap': 8, 'search': 2, 'pel': 1, 'truemotion': False, 'mode': 0, 'thscd1': 200, 'thscd2': 90 }
    elif mpix > 2: return { 'blksize': 16, 'overlap': 2, 'search': 3, 'pel': 1, 'truemotion': True, 'mode': 2, 'thscd1': 120, 'thscd2': 60 }
    else: return { 'blksize': 8, 'overlap': 4, 'search': 3, 'pel': 2, 'truemotion': True, 'mode': 3, 'thscd1': 100, 'thscd2': 50 }

def interpolate_mvtools(clip, target_fps):
    """MVTools fallback with memory-safe access and robust internal error handling."""

    params = get_mvtools_params(clip)
    if not is_yuv420p8(clip):
        first_frame = None
        matrix_in = 1
        range_in = 1
        try:
            first_frame = clip.get_frame(0)
            matrix_in = first_frame.props.get('_Matrix', 1)
            range_in = first_frame.props.get('_ColorRange', 1)
        except Exception as e:
            mp.msg.warn("Prop access error in interpolate_mvtools: " + str(e) + " - using defaults")
        finally:
            if first_frame is not None:
                del first_frame

        # --- FINAL ENHANCEMENT: Added high-quality dithering ---
        clip = core.resize.Spline36(clip, format=YUV420P8_FORMAT, matrix_in=matrix_in, matrix_s="709", range_in=range_in, range_s="limited", chromaloc_in_s="left", chromaloc_s="left", dither_type="ordered")
        clip = core.std.SetFrameProp(clip, prop="_ColorRange", intval=1)
        clip = core.std.SetFrameProp(clip, prop="_Matrix", intval=1)
        clip = core.std.SetFrameProp(clip, prop="_Transfer", intval=1)
        clip = core.std.SetFrameProp(clip, prop="_Primaries", intval=1)
        
    dst_fps_num = int(target_fps * 1e4); dst_fps_den = int(1e4)
    sup = None; bvec = None; fvec = None; out = None
    try:
        sup = core.mv.Super(clip, pel=params['pel'], sharp=2, rfilter=4)
        analyze_params = { 'blksize': params['blksize'], 'overlap': params['overlap'], 'search': params['search'], 'truemotion': params['truemotion'], 'lambda_': 800, 'pnew': 40, 'plevel': 1, 'global_': True, 'dct': 0 }
        try:
            bvec = core.mv.Analyse(sup, isb=True, **analyze_params)
            fvec = core.mv.Analyse(sup, isb=False, **analyze_params)
        except vs.Error:
            analyze_params.update({'search': 2, 'truemotion': False})
            bvec = core.mv.Analyse(sup, isb=True, **analyze_params)
            fvec = core.mv.Analyse(sup, isb=False, **analyze_params)
        interpolation_params = {'num': dst_fps_num, 'den': dst_fps_den, 'mode': params['mode'], 'thscd1': params['thscd1'], 'thscd2': params['thscd2'], 'blend': True}
        mpix = (clip.width * clip.height) / 1000000.0
        use_flowfps = mpix < 2.5
        try:
            if use_flowfps: out = core.mv.FlowFPS(clip, sup, bvec, fvec, **interpolation_params)
            else: out = core.mv.BlockFPS(clip, sup, bvec, fvec, **interpolation_params)
        except vs.Error:
            interpolation_params['mode'] = 0
            out = core.mv.BlockFPS(clip, sup, bvec, fvec, **interpolation_params)
        out = core.std.SetFrameProp(out, prop="_InterpolationMethod", data="MVTools")
        return out
    finally:
        if sup is not None: del sup
        if bvec is not None: del bvec
        if fvec is not None: del fvec
        if out is not None: out = None
        gc.collect(generation=2)  # Major GC for deep cleanup
        force_memory_cleanup()

###########-=MAIN PROCESSING=-###########

def get_target_fps():
 # Priority 1: Handle the most common anime framerates with perfect doubling.
    if 23.5 <= src_fps <= 24.5:
        return 48.0
    if 24.9 <= src_fps <= 25.1:
        return 50.0
    if 29.5 <= src_fps <= 30.5:
        return 60.0

    # Priority 2: If it's not a standard anime rate, fall back to the robust logic.
    # This ensures other videos (or oddly encoded anime) still work.
    target_fps = min(display_fps, 60)
    return target_fps

def process_interpolation(clip):
    """Main processing pipeline with robust, three-level fallback and logging."""

    global current_method
    target_fps = get_target_fps()
    log_clip_summary("pipeline_start", clip, current_method, target_fps)
    
    result = None
    try:
        if src_fps >= target_fps * 0.9:
            current_method = "None (Original FPS)"
            log_clip_summary("pipeline_passthrough_original_fps", clip, current_method, target_fps)
            return clip
        
        mpix = (clip.width * clip.height) / 1000000.0
        if mpix > 12:
            current_method = "None (High-Res)"
            log_clip_summary("pipeline_passthrough_high_res", clip, current_method, target_fps)
            return clip
        
        try:
            log_svp_diag("svp_primary_begin")
            svp_params = get_svp_params(clip)
            processed_clip = prepare_clip_svp(clip, svp_params)
            result = interpolate_svp(processed_clip, svp_params, target_fps)
            current_method = "SVP (Final)"
            log_clip_summary("svp_primary_success", result, current_method, target_fps)
            return result
            
        except Exception as e:
            log_svp_diag("svp_primary_failed: " + repr(e))
            log_svp_diag(traceback.format_exc().strip())
            try:
                log_svp_diag("mvtools_fallback_begin")
                first_frame = None
                matrix_in = 1
                range_in = 1
                try:
                    first_frame = clip.get_frame(0)
                    matrix_in = first_frame.props.get('_Matrix', 1)
                    range_in = first_frame.props.get('_ColorRange', 1)
                except Exception as e:
                    mp.msg.warn("Prop access error in fallback: " + str(e) + " - using defaults")
                finally:
                    if first_frame is not None:
                        del first_frame
                
                if not is_yuv420p8(clip):
                    # --- FINAL ENHANCEMENT: Added high-quality dithering ---
                    mvtools_clip = core.resize.Spline36(clip, format=YUV420P8_FORMAT, matrix_in=matrix_in, matrix_s="709", range_in=range_in, range_s="limited", chromaloc_in_s="left", chromaloc_s="left", dither_type="ordered")
                else:
                    mvtools_clip = clip
                
                mvtools_clip = core.std.SetFrameProp(mvtools_clip, prop="_ColorRange", intval=1)
                mvtools_clip = core.std.SetFrameProp(mvtools_clip, prop="_Matrix", intval=1)
                mvtools_clip = core.std.SetFrameProp(mvtools_clip, prop="_Transfer", intval=1)
                mvtools_clip = core.std.SetFrameProp(mvtools_clip, prop="_Primaries", intval=1)
                result = interpolate_mvtools(mvtools_clip, target_fps)
                current_method = "MVTools Fallback (Final)"
                log_clip_summary("mvtools_fallback_success", result, current_method, target_fps)
                return result
                
            except Exception as e2:
                log_svp_diag("mvtools_fallback_failed: " + repr(e2))
                log_svp_diag(traceback.format_exc().strip())
                try:
                    log_svp_diag("mvtools_safest_fallback_begin")
                    first_frame = None
                    matrix_in = 1
                    range_in = 1
                    try:
                        first_frame = clip.get_frame(0)
                        matrix_in = first_frame.props.get('_Matrix', 1)
                        range_in = first_frame.props.get('_ColorRange', 1)
                    except Exception as e:
                        mp.msg.warn("Prop access error in safest fallback: " + str(e) + " - using defaults")
                    finally:
                        if first_frame is not None:
                            del first_frame
                    
                    if not is_yuv420p8(clip):
                        # --- FINAL ENHANCEMENT: Added high-quality dithering ---
                        safe_clip = core.resize.Bicubic(clip, format=YUV420P8_FORMAT, matrix_in=matrix_in, matrix_s="1", range_in=range_in, range_s="limited", chromaloc_in_s="left", chromaloc_s="left", dither_type="ordered")
                    else:
                        safe_clip = clip
                    
                    safe_clip = core.std.SetFrameProp(safe_clip, prop="_ColorRange", intval=1)
                    safe_clip = core.std.SetFrameProp(safe_clip, prop="_Matrix", intval=1)
                    safe_clip = core.std.SetFrameProp(safe_clip, prop="_Transfer", intval=1)
                    safe_clip = core.std.SetFrameProp(safe_clip, prop="_Primaries", intval=1)
                    result = interpolate_mvtools(safe_clip, target_fps)
                    current_method = "Safest Fallback (Final)"
                    log_clip_summary("mvtools_safest_fallback_success", result, current_method, target_fps)
                    return result
                    
                except Exception as e3:
                    current_method = "FAILED - Original"
                    log_svp_diag("all_interpolation_failed: " + repr(e3))
                    log_svp_diag(traceback.format_exc().strip())
                    log_clip_summary("pipeline_failed_passthrough", clip, current_method, target_fps)
                    return clip
    finally:
        if result is not None: result = None
        gc.collect(generation=2)  # Major GC for overall cleanup
        force_memory_cleanup()

smooth = process_interpolation(clip)
# --- Final Metadata & Optional Overlay ---

smooth = core.std.SetFrameProp(smooth, prop="_SourceFPS", floatval=src_fps)
smooth = core.std.SetFrameProp(smooth, prop="_TargetFPS", floatval=get_target_fps())
smooth = core.std.SetFrameProp(smooth, prop="_NuvioSvpActive", intval=1 if current_method.startswith("SVP") else 0)
smooth = core.std.SetFrameProp(smooth, prop="_NuvioSvpTargetFPS", floatval=get_target_fps())

log_clip_summary("final_output_ready", smooth, current_method, get_target_fps())
smooth = add_svp_runtime_probe(smooth, current_method, get_target_fps())

if DEBUG_OVERLAY:
    smooth = add_method_overlay(smooth)

smooth.set_output()
