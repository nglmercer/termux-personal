"""Errors surfaced by the local control client."""


class ControlError(Exception):
    pass


class ProtocolError(ControlError):
    def __init__(self, message: str, *, outcome_unknown: bool = False):
        super().__init__(message)
        self.outcome_unknown = outcome_unknown


class UnavailableError(ControlError):
    """The current server does not advertise the requested method."""


class TransportError(ControlError):
    def __init__(self, message: str, *, outcome_unknown: bool = False):
        super().__init__(message)
        self.outcome_unknown = outcome_unknown


class RpcError(ControlError):
    def __init__(self, code: int, message: str, data=None):
        super().__init__(message)
        self.code = code
        self.data = data
        self.outcome_unknown = message == "OUTCOME_UNKNOWN" or (
            isinstance(data, dict) and data.get("code") == "OUTCOME_UNKNOWN"
        )
