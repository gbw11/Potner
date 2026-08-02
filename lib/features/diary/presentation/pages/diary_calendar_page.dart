import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:potner_app/core/navigation/app_navigation_origin.dart';
import 'package:potner_app/core/theme/app_theme.dart';
import 'package:potner_app/features/diary/data/diary_repository_impl.dart';
import 'package:potner_app/features/diary/domain/diary_models.dart';
import 'package:potner_app/features/plant/domain/my_plant.dart';
import 'package:potner_app/features/plant/presentation/plant_providers.dart';

/// 한 달 치 일기 목록이다. family 인자는 값 비교가 되도록 record 를 쓴다.
final monthlyDiariesProvider = FutureProvider.autoDispose
    .family<List<DiarySummary>, ({String plantId, int year, int month})>((
      ref,
      arg,
    ) {
      final from = DateTime(arg.year, arg.month, 1);
      final to = DateTime(arg.year, arg.month + 1, 0);
      return ref
          .read(diaryRepositoryProvider)
          .getDiaries(plantId: arg.plantId, from: from, to: to);
    });

/// 식물 일기 달력이다. 일기가 있는 날을 표시하고 선택한 날의 일기로 이어 준다.
class DiaryCalendarPage extends ConsumerStatefulWidget {
  const DiaryCalendarPage({super.key});

  @override
  ConsumerState<DiaryCalendarPage> createState() => _DiaryCalendarPageState();
}

class _DiaryCalendarPageState extends ConsumerState<DiaryCalendarPage> {
  MyPlant? _plant;
  late DateTime _month;
  late DateTime _selectedDate;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _month = DateTime(now.year, now.month);
    _selectedDate = DateTime(now.year, now.month, now.day);
  }

  @override
  Widget build(BuildContext context) {
    final plants = ref.watch(myPlantsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('식물 일기'),
        centerTitle: true,
        leading: BackButton(
          key: const Key('diary_calendar_back'),
          onPressed: () =>
              returnFromSharedPage(context, fallbackLocation: '/growth'),
        ),
      ),
      body: SafeArea(
        child: plants.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    plantErrorMessage(error, '식물 목록을 불러오지 못했습니다.'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: AppColors.textMuted),
                  ),
                  const SizedBox(height: 16),
                  FilledButton.tonal(
                    key: const Key('diary_calendar_retry'),
                    onPressed: () => ref.invalidate(myPlantsProvider),
                    child: const Text('다시 시도'),
                  ),
                ],
              ),
            ),
          ),
          data: (items) {
            if (items.isEmpty) {
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(28),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(
                        Icons.menu_book_outlined,
                        key: Key('diary_empty_icon'),
                        size: 52,
                        color: AppColors.primarySoft,
                      ),
                      const SizedBox(height: 14),
                      const Text(
                        '식물을 등록하면 매일 성장 일기가 기록돼요.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: AppColors.textMuted,
                          height: 1.5,
                        ),
                      ),
                      const SizedBox(height: 16),
                      FilledButton(
                        key: const Key('diary_register_plant'),
                        onPressed: () => context.go(
                          withCurrentNavigationOrigin(
                            context,
                            '/plants/register',
                          ),
                        ),
                        child: const Text('내 식물 등록하기'),
                      ),
                    ],
                  ),
                ),
              );
            }
            final plant = _plant ?? items.first;
            return _buildBody(plant, items);
          },
        ),
      ),
    );
  }

  Widget _buildBody(MyPlant plant, List<MyPlant> plants) {
    final diaries = ref.watch(
      monthlyDiariesProvider((
        plantId: plant.plantId,
        year: _month.year,
        month: _month.month,
      )),
    );

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (plants.length > 1) ...[
                DropdownButtonFormField<String>(
                  key: const Key('diary_plant_selector'),
                  initialValue: plant.plantId,
                  decoration: const InputDecoration(labelText: '식물'),
                  items: [
                    for (final item in plants)
                      DropdownMenuItem(
                        value: item.plantId,
                        child: Text('${item.name} (${item.speciesName})'),
                      ),
                  ],
                  onChanged: (plantId) {
                    if (plantId == null) {
                      return;
                    }
                    setState(() {
                      _plant = plants.firstWhere(
                        (item) => item.plantId == plantId,
                      );
                    });
                  },
                ),
                const SizedBox(height: 16),
              ],
              Row(
                children: [
                  const Text(
                    '날짜 선택하기',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.w800,
                      color: AppColors.primary,
                    ),
                  ),
                  const Spacer(),
                  IconButton(
                    key: const Key('diary_prev_month'),
                    onPressed: () => setState(() {
                      _month = DateTime(_month.year, _month.month - 1);
                    }),
                    icon: const Icon(Icons.chevron_left_rounded),
                  ),
                  Text(
                    '${_month.year}년 ${_month.month}월',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  IconButton(
                    key: const Key('diary_next_month'),
                    onPressed: () => setState(() {
                      _month = DateTime(_month.year, _month.month + 1);
                    }),
                    icon: const Icon(Icons.chevron_right_rounded),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              diaries.when(
                loading: () => const Padding(
                  padding: EdgeInsets.symmetric(vertical: 60),
                  child: Center(child: CircularProgressIndicator()),
                ),
                error: (error, _) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 40),
                  child: Column(
                    children: [
                      Text(
                        plantErrorMessage(error, '일기를 불러오지 못했습니다.'),
                        textAlign: TextAlign.center,
                        style: const TextStyle(color: AppColors.textMuted),
                      ),
                      const SizedBox(height: 12),
                      FilledButton.tonal(
                        key: const Key('diary_month_retry'),
                        onPressed: () => ref.invalidate(
                          monthlyDiariesProvider((
                            plantId: plant.plantId,
                            year: _month.year,
                            month: _month.month,
                          )),
                        ),
                        child: const Text('다시 시도'),
                      ),
                    ],
                  ),
                ),
                data: (items) => _buildCalendarAndPreview(plant, items),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCalendarAndPreview(MyPlant plant, List<DiarySummary> diaries) {
    final byDate = <DateTime, DiarySummary>{
      for (final diary in diaries)
        DateTime(
          diary.diaryDate.year,
          diary.diaryDate.month,
          diary.diaryDate.day,
        ): diary,
    };
    final selected = byDate[_selectedDate];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _MonthCalendar(
          month: _month,
          selectedDate: _selectedDate,
          markedDates: byDate.keys.toSet(),
          onSelected: (date) => setState(() => _selectedDate = date),
        ),
        const SizedBox(height: 24),
        Row(
          children: [
            const Icon(
              Icons.calendar_today_outlined,
              size: 20,
              color: AppColors.primary,
            ),
            const SizedBox(width: 8),
            Text(
              '${_selectedDate.year}년 ${_selectedDate.month}월 ${_selectedDate.day}일',
              style: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w800,
                color: AppColors.primary,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        if (selected == null)
          Container(
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: AppColors.surfaceLow,
              borderRadius: BorderRadius.circular(22),
            ),
            child: const Text(
              '이 날은 기록된 일기가 없어요.',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.textMuted),
            ),
          )
        else
          Material(
            color: AppColors.surface,
            borderRadius: BorderRadius.circular(22),
            clipBehavior: Clip.antiAlias,
            elevation: 1,
            shadowColor: AppColors.primary.withValues(alpha: 0.12),
            child: InkWell(
              key: const Key('diary_preview_card'),
              onTap: () => context.go(
                withCurrentNavigationOrigin(
                  context,
                  '/growth/diary/${plant.plantId}/${selected.diaryId}',
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '❝',
                      style: TextStyle(
                        fontSize: 28,
                        color: AppColors.primarySoft,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      selected.title,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                        height: 1.5,
                      ),
                    ),
                    const SizedBox(height: 12),
                    const Align(
                      alignment: Alignment.centerRight,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            '더보기',
                            style: TextStyle(
                              color: AppColors.primary,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          Icon(
                            Icons.chevron_right_rounded,
                            size: 20,
                            color: AppColors.primary,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _MonthCalendar extends StatelessWidget {
  const _MonthCalendar({
    required this.month,
    required this.selectedDate,
    required this.markedDates,
    required this.onSelected,
  });

  final DateTime month;
  final DateTime selectedDate;
  final Set<DateTime> markedDates;
  final ValueChanged<DateTime> onSelected;

  static const _weekdayLabels = ['일', '월', '화', '수', '목', '금', '토'];

  @override
  Widget build(BuildContext context) {
    final firstDay = DateTime(month.year, month.month, 1);
    final daysInMonth = DateTime(month.year, month.month + 1, 0).day;
    // DateTime.weekday 는 월=1…일=7 인데 달력은 일요일에서 시작한다.
    final leadingEmpty = firstDay.weekday % 7;

    final cells = <Widget>[
      for (final label in _weekdayLabels)
        Center(
          child: Text(
            label,
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: AppColors.textMuted,
            ),
          ),
        ),
      for (var i = 0; i < leadingEmpty; i++) const SizedBox.shrink(),
      for (var day = 1; day <= daysInMonth; day++)
        _DayCell(
          date: DateTime(month.year, month.month, day),
          selected:
              selectedDate.year == month.year &&
              selectedDate.month == month.month &&
              selectedDate.day == day,
          marked: markedDates.contains(DateTime(month.year, month.month, day)),
          onSelected: onSelected,
        ),
    ];

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.surfaceLow,
        borderRadius: BorderRadius.circular(22),
      ),
      child: GridView.count(
        crossAxisCount: 7,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        children: cells,
      ),
    );
  }
}

class _DayCell extends StatelessWidget {
  const _DayCell({
    required this.date,
    required this.selected,
    required this.marked,
    required this.onSelected,
  });

  final DateTime date;
  final bool selected;
  final bool marked;
  final ValueChanged<DateTime> onSelected;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      key: Key(
        'diary_day_${date.year}-${date.month.toString().padLeft(2, '0')}-'
        '${date.day.toString().padLeft(2, '0')}',
      ),
      customBorder: const CircleBorder(),
      onTap: () => onSelected(date),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 32,
            height: 32,
            alignment: Alignment.center,
            decoration: selected
                ? const BoxDecoration(
                    color: AppColors.primary,
                    shape: BoxShape.circle,
                  )
                : null,
            child: Text(
              '${date.day}',
              style: TextStyle(
                fontWeight: FontWeight.w600,
                color: selected ? Colors.white : AppColors.text,
              ),
            ),
          ),
          SizedBox(
            height: 6,
            child: !selected && marked
                ? const Icon(
                    Icons.circle,
                    size: 5,
                    color: AppColors.primaryContainer,
                  )
                : null,
          ),
        ],
      ),
    );
  }
}
