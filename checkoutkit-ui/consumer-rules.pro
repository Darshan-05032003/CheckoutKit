# Consumer R8 rules for checkoutkit-ui.
# The payment sheet's ViewModel is created through an explicit viewModelFactory initializer, not by
# reflection, and the module has no Parcelable/Serializable state, so nothing has to be kept.
# This file exists to document that decision; checkoutkit-core ships its own rules.
