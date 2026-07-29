from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional


@dataclass
class ClimateReading:
    temperature_c: Optional[float]
    humidity_pct: Optional[float]


@dataclass
class LightReading:
    lux: Optional[float]


@dataclass
class SoilReading:
    raw: Optional[int]
    moisture_pct: Optional[float]


class ClimateSensor(ABC):
    @abstractmethod
    def read(self) -> ClimateReading:
        raise NotImplementedError


class LightSensor(ABC):
    @abstractmethod
    def read(self) -> LightReading:
        raise NotImplementedError


class SoilSensor(ABC):
    @abstractmethod
    def read(self) -> SoilReading:
        raise NotImplementedError
