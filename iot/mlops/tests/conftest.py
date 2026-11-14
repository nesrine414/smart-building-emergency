"""Rend features.py, train.py et infer.py importables depuis les tests."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
