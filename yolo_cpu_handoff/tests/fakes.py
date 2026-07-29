from dataclasses import dataclass


class Scalar:
    def __init__(self, value):
        self._value = value

    def item(self):
        return self._value


class Coordinates:
    def __init__(self, values):
        self._values = values

    def tolist(self):
        return [self._values]


@dataclass
class FakeBox:
    class_id: int
    confidence: float
    coordinates: list[float]

    @property
    def cls(self):
        return Scalar(self.class_id)

    @property
    def conf(self):
        return Scalar(self.confidence)

    @property
    def xyxy(self):
        return Coordinates(self.coordinates)


@dataclass
class FakeResult:
    boxes: list[FakeBox]


class FakeModel:
    def __init__(self, names=None, boxes=None, prediction_error=None):
        self.names = names or {
            0: "germination",
            1: "vegetative",
            2: "flowering",
        }
        self._boxes = boxes or []
        self._prediction_error = prediction_error
        self.predict_kwargs = None

    def predict(self, **kwargs):
        self.predict_kwargs = kwargs
        if self._prediction_error is not None:
            raise self._prediction_error
        return [FakeResult(self._boxes)]


class FakeFactory:
    def __init__(self, model=None, load_error=None):
        self.model = model or FakeModel()
        self.load_error = load_error
        self.paths = []

    def __call__(self, weights_path):
        self.paths.append(weights_path)
        if self.load_error is not None:
            raise self.load_error
        return self.model
