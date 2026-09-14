import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

/// 같은 화면을 홈·마이·전체 메뉴에서 공유할 때 돌아갈 위치를 보존한다.
enum AppNavigationOrigin {
  home('/'),
  my('/my'),
  menu('/menu'),
  growth('/growth');

  const AppNavigationOrigin(this.location);

  final String location;

  static AppNavigationOrigin? fromContext(BuildContext context) {
    final value = GoRouterState.of(
      context,
    ).uri.queryParameters[navigationOriginQueryKey];
    for (final origin in values) {
      if (origin.name == value) {
        return origin;
      }
    }
    return null;
  }
}

const navigationOriginQueryKey = 'origin';
const navigationReturnQueryKey = 'returnTo';

String withNavigationOrigin(String location, AppNavigationOrigin origin) {
  final uri = Uri.parse(location);
  return uri
      .replace(
        queryParameters: {
          ...uri.queryParameters,
          navigationOriginQueryKey: origin.name,
        },
      )
      .toString();
}

String withCurrentNavigationOrigin(BuildContext context, String location) {
  final origin = AppNavigationOrigin.fromContext(context);
  return origin == null ? location : withNavigationOrigin(location, origin);
}

String withPreviousNavigationLocation(BuildContext context, String location) {
  final destination = Uri.parse(withCurrentNavigationOrigin(context, location));
  return destination
      .replace(
        queryParameters: {
          ...destination.queryParameters,
          navigationReturnQueryKey: GoRouterState.of(context).uri.toString(),
        },
      )
      .toString();
}

void returnFromSharedPage(
  BuildContext context, {
  required String fallbackLocation,
}) {
  final returnTo = GoRouterState.of(
    context,
  ).uri.queryParameters[navigationReturnQueryKey];
  if (_isSafeNavigationLocation(returnTo)) {
    context.go(returnTo!);
    return;
  }
  final origin = AppNavigationOrigin.fromContext(context);
  if (origin != null) {
    context.go(origin.location);
    return;
  }
  if (context.canPop()) {
    context.pop();
    return;
  }
  context.go(fallbackLocation);
}

bool _isSafeNavigationLocation(String? location) {
  if (location == null || !location.startsWith('/')) {
    return false;
  }
  final uri = Uri.tryParse(location);
  if (uri == null) {
    return false;
  }
  return uri.path != '/login' &&
      uri.path != '/splash' &&
      !uri.path.startsWith('/signup');
}
