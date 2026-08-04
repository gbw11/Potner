import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:scrollable_positioned_list/scrollable_positioned_list.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/conversation/application/conversation_controller.dart';
import 'package:potner_app/features/conversation/domain/conversation_models.dart';
import 'package:potner_app/features/device/domain/device_models.dart';
import 'package:potner_app/features/device/presentation/pages/device_management_page.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 로봇과 나눈 대화를 보는 화면이다.
///
/// 읽기만 한다. 대화는 로봇에게 말을 걸어서 하는 것이고(폰 음성 또는 로봇 화면), 이곳은 그 기록을
/// 되돌아보는 자리다. 입력창을 두면 여기서 말을 걸 수 있다고 오해한다.
///
/// 위로 올리면 이전 대화를 더 받는다. 커서(`nextBeforeSeq`)로 받으므로 스크롤하는 동안 새 발화가
/// 들어와도 항목이 밀리지 않는다.
class ConversationPage extends ConsumerStatefulWidget {
  const ConversationPage({super.key});

  @override
  ConsumerState<ConversationPage> createState() => _ConversationPageState();
}

class _ConversationPageState extends ConsumerState<ConversationPage> {
  /// 인덱스로 옮길 수 있는 컨트롤러다. `ScrollController` 는 픽셀 오프셋만 받는데, 말풍선 높이가
  /// 글 길이마다 달라 "그 날 첫 발화" 의 오프셋을 계산할 수 없다.
  final _itemScrollController = ItemScrollController();
  final _itemPositionsListener = ItemPositionsListener.create();
  String? _requestedPlantId;

  @override
  void initState() {
    super.initState();
    _itemPositionsListener.itemPositions.addListener(_onPositionsChanged);
  }

  @override
  void dispose() {
    _itemPositionsListener.itemPositions.removeListener(_onPositionsChanged);
    super.dispose();
  }

  /// 목록의 끝(가장 오래된 쪽)이 보이기 시작하면 이전 대화를 미리 받는다.
  ///
  /// `reverse: true` 라 화면 위쪽이 큰 인덱스다. 보이는 것 중 가장 큰 인덱스가 마지막 항목에
  /// 가까워지면 더 받는다.
  void _onPositionsChanged() {
    final positions = _itemPositionsListener.itemPositions.value;
    if (positions.isEmpty) {
      return;
    }
    final lastVisible = positions
        .map((position) => position.index)
        .reduce((a, b) => a > b ? a : b);
    final state = ref.read(conversationControllerProvider);
    if (lastVisible >= state.messages.length - 3) {
      ref.read(conversationControllerProvider.notifier).loadOlder();
    }
  }

  @override
  Widget build(BuildContext context) {
    final robots = ref.watch(robotsProvider);
    final state = ref.watch(conversationControllerProvider);

    // 이동 대상이 정해지면 그 자리로 옮긴다. 목록이 그려진 뒤여야 하므로 프레임 뒤로 미룬다.
    final targetSeq = state.jumpTargetSeq;
    if (targetSeq != null) {
      final index = state.messages.indexWhere((m) => m.seq == targetSeq);
      if (index >= 0) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted || !_itemScrollController.isAttached) {
            return;
          }
          // alignment 0 이면 그 항목이 뷰포트 시작(reverse 이므로 화면 위쪽)에 붙는다.
          // 고른 날짜의 첫 발화가 위에 오고, 아래로 내리면 그 뒤 날짜가 이어진다.
          _itemScrollController.jumpTo(index: index, alignment: 0);
          ref.read(conversationControllerProvider.notifier).clearJumpTarget();
        });
      }
    }

    final notice = state.jumpNotice;
    if (notice != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) {
          return;
        }
        ScaffoldMessenger.of(context)
          ..hideCurrentSnackBar()
          ..showSnackBar(SnackBar(content: Text(notice)));
        ref.read(conversationControllerProvider.notifier).clearJumpNotice();
      });
    }

    return Scaffold(
      key: const Key('conversation_page'),
      appBar: AppBar(
        title: const Text('로봇과 나눈 대화'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('conversation_back'),
          onPressed: () => returnFromSharedPage(context, fallbackLocation: '/'),
        ),
        actions: [
          IconButton(
            key: const Key('conversation_calendar_button'),
            tooltip: '날짜로 이동',
            // 대화가 없으면 옮길 자리가 없다.
            onPressed: state.messages.isEmpty || state.isJumping
                ? null
                : () => _pickDate(state),
            icon: const Icon(Icons.calendar_month_outlined),
          ),
        ],
      ),
      backgroundColor: AppColors.surfaceLow,
      body: SafeArea(
        child: robots.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => _Notice(
            text: plantErrorMessage(error, '장치 정보를 불러오지 못했습니다.'),
            onRetry: () => ref.invalidate(robotsProvider),
          ),
          data: (items) {
            // 대화는 식물 단위다. 배정된 식물이 없으면 볼 대화가 없다.
            final assigned = items
                .where((robot) => robot.assignedPlantId != null)
                .firstOrNull;
            if (assigned == null) {
              return const _Notice(
                key: Key('conversation_unavailable'),
                text: '배정된 식물이 없어 대화가 없어요.\n장치 관리에서 로봇을 식물에 배정해 주세요.',
              );
            }
            _ensureLoaded(assigned.assignedPlantId!);
            return _ConversationBody(
              robot: assigned,
              itemScrollController: _itemScrollController,
              itemPositionsListener: _itemPositionsListener,
            );
          },
        ),
      ),
    );
  }

  Future<void> _pickDate(ConversationState state) async {
    final newest = state.newestLocalDate ?? DateTime.now();
    // 하한은 "받아 둔 가장 오래된 날" 이 아니라 그보다 넉넉히 잡는다. 더 오래된 대화는 아직
    // 안 받았을 뿐 존재할 수 있고, 고르면 컨트롤러가 거슬러 받는다. 없으면 안내가 뜬다.
    final first = state.hasMore
        ? DateTime(newest.year - 2, newest.month, newest.day)
        : (state.oldestLocalDate ?? newest);

    final picked = await showDatePicker(
      context: context,
      initialDate: newest,
      firstDate: first,
      lastDate: newest,
      helpText: '날짜 선택',
      // 달력을 먼저 보여준다. 연·월·일을 눌러 고르는 흐름이 자연스럽다.
      initialEntryMode: DatePickerEntryMode.calendar,
    );
    if (picked == null || !mounted) {
      return;
    }
    await ref
        .read(conversationControllerProvider.notifier)
        .jumpToDate(picked);
  }

  /// 로봇 목록이 온 뒤에야 식물을 알 수 있어 build 에서 시작한다. 같은 식물로 두 번 부르지
  /// 않도록 기억해 둔다 — build 는 여러 번 불린다.
  void _ensureLoaded(String plantId) {
    if (_requestedPlantId == plantId) {
      return;
    }
    _requestedPlantId = plantId;
    Future.microtask(
      () => ref.read(conversationControllerProvider.notifier).load(plantId),
    );
  }
}

class _ConversationBody extends ConsumerWidget {
  const _ConversationBody({
    required this.robot,
    required this.itemScrollController,
    required this.itemPositionsListener,
  });

  final ManagedRobot robot;
  final ItemScrollController itemScrollController;
  final ItemPositionsListener itemPositionsListener;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(conversationControllerProvider);
    final controller = ref.read(conversationControllerProvider.notifier);

    if (state.phase == ConversationPhase.loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (state.phase == ConversationPhase.failure) {
      return _Notice(
        key: const Key('conversation_error'),
        text: state.message ?? '대화를 불러오지 못했습니다.',
        onRetry: () => controller.load(robot.assignedPlantId!),
      );
    }
    if (state.isEmpty) {
      return _Notice(
        key: const Key('conversation_empty'),
        text:
            '아직 나눈 대화가 없어요.\n'
            '${robot.assignedPlantName ?? '식물'}에게 말을 걸면 여기에 쌓입니다.',
      );
    }

    final messages = state.messages;
    return Stack(
      children: [
        _buildList(state, messages),
        // 날짜로 이동하는 동안은 여러 페이지를 이어 받으므로 진행을 알려야 한다.
        if (state.isJumping)
          const Positioned.fill(
            child: ColoredBox(
              color: Color(0x66000000),
              child: Center(
                child: Card(
                  child: Padding(
                    padding: EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2.5),
                        ),
                        SizedBox(width: 12),
                        Text('그날 대화를 찾는 중이에요'),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildList(
    ConversationState state,
    List<ConversationMessage> messages,
  ) {
    return ScrollablePositionedList.builder(
      key: const Key('conversation_list'),
      itemScrollController: itemScrollController,
      itemPositionsListener: itemPositionsListener,
      // 채팅은 아래가 최신이다. reverse 로 두면 새 발화가 와도 스크롤이 튀지 않는다.
      reverse: true,
      padding: const EdgeInsets.fromLTRB(14, 16, 14, 16),
      // 위쪽 끝에 이전 대화 로딩 표시를 한 칸 더 둔다.
      itemCount: messages.length + 1,
      itemBuilder: (context, index) {
        if (index == messages.length) {
          return _OlderLoader(
            isLoading: state.isLoadingOlder,
            hasMore: state.hasMore,
          );
        }

        final message = messages[index];
        // reverse 목록에서 index + 1 은 화면상 바로 위(더 오래된 발화)다. 날짜가 다르면 이
        // 발화가 그 날의 첫 발화이므로 위에 구분선을 넣는다.
        final older = index + 1 < messages.length ? messages[index + 1] : null;
        final showDate = older == null
            // 아직 더 받을 것이 있으면 이 발화가 그 날의 첫 발화라고 단정할 수 없다.
            ? !state.hasMore
            : !_isSameLocalDay(message.createdAt, older.createdAt);

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (showDate) _DateSeparator(utcTime: message.createdAt),
            _MessageBubble(message: message, plantName: robot.assignedPlantName),
          ],
        );
      },
    );
  }

  /// 로컬 시각 기준으로 비교한다. UTC 로 비교하면 자정 근처 대화가 전날로 묶인다.
  bool _isSameLocalDay(DateTime a, DateTime b) {
    final x = a.toLocal();
    final y = b.toLocal();
    return x.year == y.year && x.month == y.month && x.day == y.day;
  }
}

class _MessageBubble extends StatelessWidget {
  const _MessageBubble({required this.message, this.plantName});

  final ConversationMessage message;
  final String? plantName;

  @override
  Widget build(BuildContext context) {
    final isMine = message.role.isMine;
    final time = _timeLabel(message.createdAt);

    final bubble = Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: isMine ? AppColors.primarySoft : AppColors.surface,
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(16),
          topRight: const Radius.circular(16),
          bottomLeft: Radius.circular(isMine ? 16 : 4),
          bottomRight: Radius.circular(isMine ? 4 : 16),
        ),
        border: Border.all(color: AppColors.outline.withValues(alpha: 0.5)),
      ),
      child: Text(
        message.content,
        style: const TextStyle(color: AppColors.text, height: 1.45),
      ),
    );

    final stamp = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 6),
      child: Text(
        time,
        style: const TextStyle(color: AppColors.textMuted, fontSize: 11),
      ),
    );

    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: isMine
            ? CrossAxisAlignment.end
            : CrossAxisAlignment.start,
        children: [
          // 로봇 말풍선에만 이름을 붙인다. 내 말에 내 이름을 다는 채팅은 없다.
          if (!isMine)
            Padding(
              padding: const EdgeInsets.only(left: 6, bottom: 4),
              child: Text(
                plantName ?? '내 식물',
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          Row(
            mainAxisAlignment: isMine
                ? MainAxisAlignment.end
                : MainAxisAlignment.start,
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              if (isMine) stamp,
              // 말풍선이 화면을 꽉 채우지 않게 막는다. 긴 답변이 와도 좌우 여백이 남아야
              // 누가 말한 것인지 방향으로 읽힌다.
              Flexible(
                child: Align(
                  alignment: isMine
                      ? Alignment.centerRight
                      : Alignment.centerLeft,
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      maxWidth: MediaQuery.sizeOf(context).width * 0.72,
                    ),
                    child: bubble,
                  ),
                ),
              ),
              if (!isMine) stamp,
            ],
          ),
        ],
      ),
    );
  }

  String _timeLabel(DateTime utcTime) {
    final local = utcTime.toLocal();
    final isMorning = local.hour < 12;
    final hour12 = local.hour % 12 == 0 ? 12 : local.hour % 12;
    final minute = local.minute.toString().padLeft(2, '0');
    return '${isMorning ? '오전' : '오후'} $hour12:$minute';
  }
}

/// 날짜가 바뀌는 자리에 넣는 구분선이다.
///
/// 서버가 만들어 주지 않는다. 어느 단위로 묶어 보여줄지는 화면이 정할 일이고, 서버가 묶어 보내면
/// 응답 형식이 화면 구조를 가둔다.
class _DateSeparator extends StatelessWidget {
  const _DateSeparator({required this.utcTime});

  final DateTime utcTime;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
          decoration: BoxDecoration(
            color: AppColors.outline.withValues(alpha: 0.35),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Text(
            _label(utcTime.toLocal()),
            style: const TextStyle(
              color: AppColors.textMuted,
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
      ),
    );
  }

  String _label(DateTime local) {
    const weekdays = ['월', '화', '수', '목', '금', '토', '일'];
    final weekday = weekdays[local.weekday - 1];
    return '${local.year}년 ${local.month}월 ${local.day}일 $weekday요일';
  }
}

/// 목록 위쪽 끝이다. 이전 대화를 받는 중이면 표시하고, 더 없으면 대화의 시작임을 알린다.
class _OlderLoader extends StatelessWidget {
  const _OlderLoader({required this.isLoading, required this.hasMore});

  final bool isLoading;
  final bool hasMore;

  @override
  Widget build(BuildContext context) {
    if (isLoading) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 16),
        child: Center(
          child: SizedBox(
            width: 22,
            height: 22,
            child: CircularProgressIndicator(strokeWidth: 2.5),
          ),
        ),
      );
    }
    if (hasMore) {
      // 스크롤이 여기에 닿으면 리스너가 받아 오므로 버튼을 두지 않는다.
      return const SizedBox(height: 24);
    }
    return const Padding(
      padding: EdgeInsets.only(bottom: 14),
      child: Center(
        child: Text(
          '대화의 시작이에요',
          key: Key('conversation_start_marker'),
          style: TextStyle(color: AppColors.textMuted, fontSize: 12),
        ),
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({required this.text, this.onRetry, super.key});

  final String text;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding
        (padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              text,
              textAlign: TextAlign.center,
              style: const TextStyle(color: AppColors.textMuted, height: 1.5),
            ),
            if (onRetry != null) ...[
              const SizedBox(height: 16),
              FilledButton.tonal(
                key: const Key('conversation_retry'),
                onPressed: onRetry,
                child: const Text('다시 시도'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
