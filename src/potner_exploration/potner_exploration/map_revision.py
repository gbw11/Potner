"""새 지도에서만 비용이 큰 목표 재검증을 수행하도록 revision을 추적합니다."""


class MapRevisionGate:
    def __init__(self):
        self._revision = 0
        self._checked_revision = -1

    @property
    def needs_check(self) -> bool:
        return self._checked_revision != self._revision

    @property
    def revision(self) -> int:
        return self._revision

    def is_current(self, revision: int) -> bool:
        return revision == self._revision

    def accepts_analysis(self, revision: int, has_frontiers: bool) -> bool:
        """오래된 결과는 안전 검증할 frontier 제안이 있을 때만 허용합니다."""
        return self.is_current(revision) or has_frontiers

    def mark_map(self):
        self._revision += 1

    def mark_checked(self):
        self._checked_revision = self._revision

    def reset(self):
        self._checked_revision = self._revision - 1
