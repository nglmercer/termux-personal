TERMUX_PKG_HOMEPAGE=https://github.com/nglmercer/termux-personal
TERMUX_PKG_DESCRIPTION="Client for the opt-in Termux local control API"
TERMUX_PKG_LICENSE="GPL-3.0"
TERMUX_PKG_MAINTAINER="@nglmercer"
TERMUX_PKG_VERSION=0.1.0+git20261028
# Draft source: replace with an accepted upstream release before publishing.
TERMUX_PKG_SRCURL=https://github.com/nglmercer/termux-personal/archive/26457a016a972b2fe7417d106ad06f637032bc7a.tar.gz
TERMUX_PKG_SHA256=73925ca30f4c93c2506575125a64fad77088c57e1fc144de080afcd7451a2190

termux_step_make() {
	$CC $CFLAGS -std=c11 -Wall -Wextra -Werror \
		"$TERMUX_PKG_SRCDIR/tools/termux-control/termux-control.c" \
		$LDFLAGS -o "$TERMUX_PKG_BUILDDIR/termux-control"
}

termux_step_make_install() {
	install -Dm755 "$TERMUX_PKG_BUILDDIR/termux-control" "$TERMUX_PREFIX/bin/termux-control"
}
