#!/usr/bin/env python3
"""Export the canonical Blockbench assets and the plugin's matching lookup table."""
import argparse
import json
import shutil
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "models/boundary-aligned"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path, help="CraftEngine resources/pipes directory")
    args = parser.parse_args()
    destination = args.destination
    states = json.loads((SOURCE / "states.json").read_text())
    assets = destination / "resourcepack/assets/pipes"
    models = assets / "models/boundary_aligned"
    textures = assets / "textures/boundary_aligned"
    shutil.copytree(SOURCE / "java-models", models, dirs_exist_ok=True, ignore=shutil.ignore_patterns("items"))
    # Remove inventory models exported by the earlier CE inventory integration.
    for source in (SOURCE / "java-models/items").glob("*.json"):
        (models / "items" / source.name).unlink(missing_ok=True)
    shutil.copytree(SOURCE / "textures", textures, dirs_exist_ok=True)
    items = {}
    for model in states["models"]:
        items[f"pipes:display/{model['material']}/{model['family']}"] = {
            "material": "paper",
            "model": {"type": "minecraft:model",
                      "path": "pipes:boundary_aligned/" + model["model"].removeprefix("java-models/").removesuffix(".json")},
        }
    configuration = destination / "configuration"
    configuration.mkdir(parents=True, exist_ok=True)
    (configuration / "items.yml").write_text(yaml.safe_dump({"items": items}, allow_unicode=True, sort_keys=False))
    (destination / "pack.yml").write_text(yaml.safe_dump({
        "namespace": "pipes", "author": "Pipes", "version": "1.0.0",
        "description": "Pipes 管道展示实体模型与贴图",
    }, allow_unicode=True, sort_keys=False))

    lookup = {"rotations": {r["id"]: r["quaternion_xyzw"] for r in states["rotations"]},
              "families": ["regular"] + [f["id"] for f in states["cornerFamilies"]],
              "regular": {}, "corner": {}}
    for state in states["states"]:
        if state["material"] != "copper":
            continue
        key = state["primary"] if state["kind"] == "regular" else str(state["inputMask"] | state["outputMask"] << 6)
        lookup[state["kind"]].setdefault(key, {
            "family": Path(state["model"]).stem, "rotation": state["rotation"],
        })
    (ROOT / "src/main/resources/pipe-models.json").write_text(json.dumps(lookup, separators=(",", ":")) + "\n")
    (destination / "README.md").write_text("""# Pipes 资源

CE 仅注册 `pipes:display/*` 下的 192 个内部展示物品并打包资源，用于 ItemDisplay；涂蜡铜与铜共用模型。
不注册可放置物品、方块或放置行为。背包、发放、放置、头颅方块交互载体、破坏、掉落与持久化继续使用 Pipes 原有物品和逻辑。
192 个管道模型 JSON、28 张贴图直接来自 `models/boundary-aligned`；原始物品模型仍保留在模型源目录，不导出到 CE。

先加载这套 CE 物品配置并生成、分发资源包，再启用新版 Pipes。
CE 完成加载后 Pipes 才启动世界扫描；CE 重载与 `/pipes reload` 均会刷新已加载管道。
未加载区块中的旧管道会在实体加载时迁移，背包和掉落继续使用原有头颅物品及 PDC 标记。
旧展示实体不复用，位置、朝向和管道变体沿用实体上的持久标签。
`display.yml` 仅沿用物理头颅方块的纹理，展示实体不再使用头颅纹理、伸缩和容器偏移设置。

重新导出：在 Pipes 仓库运行 `python3 scripts/export_craftengine.py <本目录>`。
""")
    print(f"Exported {len(items)} CE display items, {len(states['models'])} pipe models, 28 textures to {destination}")


if __name__ == "__main__":
    main()
