class BdsCtlError(RuntimeError):
    """A user-actionable artifact acquisition failure."""


class MetadataError(BdsCtlError):
    pass


class SecurityError(BdsCtlError):
    pass


class ValidationError(BdsCtlError):
    pass

