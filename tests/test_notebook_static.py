import ast
import json
import random
import re
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np


ROOT = Path(__file__).resolve().parents[1]
NOTEBOOK = ROOT / "plant_growth_detection.ipynb"
REQUIREMENTS = ROOT / "requirements.txt"


def _load_notebook():
    assert NOTEBOOK.exists(), "plant_growth_detection.ipynb가 없습니다."
    return json.loads(NOTEBOOK.read_text(encoding="utf-8"))


def test_notebook_json_and_code_cells_are_valid():
    notebook = _load_notebook()
    assert notebook["nbformat"] == 4
    assert notebook["cells"]
    for index, cell in enumerate(notebook["cells"]):
        if cell["cell_type"] == "code":
            ast.parse("".join(cell["source"]), filename=f"cell_{index}")


def test_notebook_contains_required_workflow():
    notebook = _load_notebook()
    all_source = "\n".join("".join(cell["source"]) for cell in notebook["cells"])
    required_markers = [
        "ROBOFLOW_API_KEY",
        "cleaned_dataset",
        "leakage_report.csv",
        "PHASH_DUPLICATE_MAX",
        "PHASH_REVIEW_MAX",
        "SEQUENCE_PATTERNS",
        "review_required",
        "component_size",
        "model.train",
        "model.val",
        "best.pt",
        "CUDA",
        "is_relative_to",
        "ap_class_index",
        "dataset_root",
        "component_membership",
        "component_size_distribution",
    ]
    for marker in required_markers:
        assert marker in all_source, f"필수 항목이 없습니다: {marker}"


def test_korean_explanations_and_comments_are_present():
    notebook = _load_notebook()
    markdown = "\n".join(
        "".join(cell["source"])
        for cell in notebook["cells"]
        if cell["cell_type"] == "markdown"
    )
    assert len(markdown) > 1_000
    assert any("가" <= char <= "힣" for char in markdown)


def test_requirements_contains_runtime_packages():
    assert REQUIREMENTS.exists(), "requirements.txt가 없습니다."
    requirements = REQUIREMENTS.read_text(encoding="utf-8").lower()
    for package in [
        "jupyterlab",
        "roboflow",
        "ultralytics",
        "torch",
        "pandas",
        "imagehash",
        "pyyaml",
    ]:
        assert package in requirements
    assert "torch==2.5.1" in requirements
    assert "torchvision==0.20.1" in requirements
    assert "numpy>=1.26,<2.4" in requirements
    assert "python-dotenv" in requirements


def test_dotenv_is_loaded_before_api_key_lookup():
    notebook = _load_notebook()
    source = "\n".join("".join(cell["source"]) for cell in notebook["cells"])
    import_position = source.index("from dotenv import load_dotenv")
    load_position = source.index("load_dotenv(", import_position)
    lookup_position = source.index('os.getenv("ROBOFLOW_API_KEY")')
    assert import_position < load_position < lookup_position


def test_gpu_four_configuration_is_guarded_and_reported_correctly():
    notebook = _load_notebook()
    config = "".join(notebook["cells"][4]["source"])
    assert "GPU_INDEX = 4" in config
    assert "torch.cuda.device_count() <= GPU_INDEX" in config
    assert "DEVICE = GPU_INDEX" in config
    assert "torch.cuda.get_device_name(DEVICE)" in config
    assert "torch.cuda.get_device_name(0)" not in config


def test_destructive_cleanup_is_guarded_before_rmtree():
    notebook = _load_notebook()
    source = "\n".join("".join(cell["source"]) for cell in notebook["cells"])
    guard = source.index("paths_overlap = (")
    deletion = source.index("shutil.rmtree(CLEAN_DATASET_DIR)")
    assert guard < deletion
    between = source[guard:deletion]
    assert "if paths_overlap:" in between
    assert "raise ValueError" in between
    assert "is_relative_to" in between


def test_metrics_follow_ultralytics_ap_class_index():
    notebook = _load_notebook()
    source = "\n".join("".join(cell["source"]) for cell in notebook["cells"])
    start = source.index("def per_class_metrics_table")
    end = source.index("val_class_metrics =", start)
    function_source = source[start:end]
    assert "metrics.box.ap_class_index" in function_source
    assert "position_by_class.get(class_id)" in function_source
    assert "metrics.box.ap[position]" in function_source


def test_review_pairs_are_reported_without_union_and_membership_is_complete():
    notebook = _load_notebook()
    source = "\n".join("".join(cell["source"]) for cell in notebook["cells"])
    review_start = source.index(
        "elif PHASH_REVIEW_MIN <= distance <= PHASH_REVIEW_MAX:"
    )
    review_end = source.index("tree.add(", review_start)
    assert "union_find.union" not in source[review_start:review_end]

    membership_marker = source.index('"component_membership"')
    membership_start = source.rfind("for record in valid_records:", 0, membership_marker)
    membership_end = source.index("for left, right, distance in duplicate_edges:", membership_start)
    membership_source = source[membership_start:membership_end]
    assert '"component_membership"' in membership_source
    assert "**component_stats[record[\"component_root\"]]" in membership_source


def test_roboflow_target_directory_is_not_created_before_download():
    notebook = _load_notebook()
    source = "\n".join("".join(cell["source"]) for cell in notebook["cells"])
    branch_start = source.index("if raw_yaml.exists():")
    download_call = source.index("downloaded = version.download(", branch_start)
    before_download = source[branch_start:download_call]
    assert "RAW_DATASET_DIR.mkdir" not in before_download


def test_filename_separator_regex_is_valid():
    notebook = _load_notebook()
    source = "\n".join("".join(cell["source"]) for cell in notebook["cells"])
    assert 're.sub(r"[-_.]+", "_", key)' in source
    assert 're.split(r"[-_.]+", stem)' in source


def _extract_function(function_name):
    notebook = _load_notebook()
    for cell in notebook["cells"]:
        if cell["cell_type"] != "code":
            continue
        tree = ast.parse("".join(cell["source"]))
        for node in tree.body:
            if isinstance(node, ast.FunctionDef) and node.name == function_name:
                return ast.Module(body=[node], type_ignores=[])
    raise AssertionError(f"{function_name} 함수를 찾지 못했습니다.")


def test_sequence_key_removes_roboflow_suffix_file_marker_and_frame():
    notebook = _load_notebook()
    config_source = "".join(notebook["cells"][4]["source"])
    config_tree = ast.parse(config_source)
    patterns_node = next(
        node
        for node in config_tree.body
        if isinstance(node, ast.Assign)
        and any(
            isinstance(target, ast.Name) and target.id == "SEQUENCE_PATTERNS"
            for target in node.targets
        )
    )
    patterns = ast.literal_eval(patterns_node.value)
    namespace = {
        "re": re,
        "SEQUENCE_PATTERNS": patterns,
        "empty_sequence_key_stems": set(),
    }
    exec(compile(_extract_function("make_sequence_key"), "<sequence-test>", "exec"), namespace)
    result = namespace["make_sequence_key"](
        "weed_copper-leaf_w0_90_00000021_jpg.rf.2aaad653b9a3ea4ae4493efe6a3fb03e"
    )
    assert result == "weed_copper_leaf_w0_90"


def test_component_assignment_tracks_global_target_ratios():
    namespace = {
        "np": np,
        "random": random,
        "Counter": Counter,
        "defaultdict": defaultdict,
        "TARGET_CLASSES": ["germination", "vegetative", "flowering"],
        "SPLIT_RATIOS": {"train": 0.70, "val": 0.20, "test": 0.10},
        "SEED": 42,
    }
    exec(compile(_extract_function("assign_components"), "<split-test>", "exec"), namespace)
    records = [
        {"component_root": index, "class_ids": [index % 3]}
        for index in range(300)
    ]
    assignments = namespace["assign_components"](records)
    counts = Counter(assignments.values())
    assert counts == {"train": 210, "val": 60, "test": 30}


def test_large_component_does_not_get_forced_into_small_split():
    namespace = {
        "np": np,
        "random": random,
        "Counter": Counter,
        "defaultdict": defaultdict,
        "TARGET_CLASSES": ["germination", "vegetative", "flowering"],
        "SPLIT_RATIOS": {"train": 0.70, "val": 0.20, "test": 0.10},
        "SEED": 42,
    }
    exec(compile(_extract_function("assign_components"), "<large-group-test>", "exec"), namespace)
    records = [
        {"component_root": 0, "class_ids": [index % 3]}
        for index in range(17)
    ]
    records.extend(
        {"component_root": index, "class_ids": [index % 3]}
        for index in range(1, 84)
    )
    assignments = namespace["assign_components"](records)
    image_counts = Counter(assignments[record["component_root"]] for record in records)
    assert assignments[0] == "train"
    assert abs(image_counts["train"] - 70) <= 1
    assert abs(image_counts["val"] - 20) <= 1
    assert abs(image_counts["test"] - 10) <= 1
