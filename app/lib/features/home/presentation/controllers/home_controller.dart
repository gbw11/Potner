import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:potner_app/core/api/api_exception.dart';
import 'package:potner_app/features/home/data/home_repository_impl.dart';
import 'package:potner_app/features/home/domain/home_dashboard.dart';
import 'package:potner_app/features/home/domain/home_repository.dart';

final homeControllerProvider =
    NotifierProvider.autoDispose<HomeController, HomeState>(HomeController.new);

enum HomeStatus { loading, loaded, empty, failure }

class HomeState {
  const HomeState({
    required this.status,
    this.plants = const [],
    this.selectedPlantId,
    this.dashboard,
    this.message,
    this.isRefreshing = false,
    this.unreadAlertCount = 0,
  });

  const HomeState.loading() : this(status: HomeStatus.loading);

  final HomeStatus status;
  final List<HomePlant> plants;
  final String? selectedPlantId;
  final HomeDashboard? dashboard;
  final String? message;
  final bool isRefreshing;
  final int unreadAlertCount;

  HomeState copyWith({
    HomeStatus? status,
    List<HomePlant>? plants,
    String? selectedPlantId,
    HomeDashboard? dashboard,
    String? message,
    bool? isRefreshing,
    int? unreadAlertCount,
  }) {
    return HomeState(
      status: status ?? this.status,
      plants: plants ?? this.plants,
      selectedPlantId: selectedPlantId ?? this.selectedPlantId,
      dashboard: dashboard ?? this.dashboard,
      message: message,
      isRefreshing: isRefreshing ?? this.isRefreshing,
      unreadAlertCount: unreadAlertCount ?? this.unreadAlertCount,
    );
  }
}

class HomeController extends Notifier<HomeState> {
  // build()는 provider 일시정지 후 재개될 때 같은 인스턴스에서 다시 실행될 수
  // 있으므로 late final 로 두면 두 번째 할당에서 죽는다.
  late HomeRepository _repository;
  Future<void>? _loadFuture;

  @override
  HomeState build() {
    _repository = ref.read(homeRepositoryProvider);
    Future.microtask(load);
    return const HomeState.loading();
  }

  Future<void> load() {
    final pending = _loadFuture;
    if (pending != null) {
      return pending;
    }

    late final Future<void> load;
    load = _load().whenComplete(() {
      if (identical(_loadFuture, load)) {
        _loadFuture = null;
      }
    });
    _loadFuture = load;
    return load;
  }

  Future<void> _load() async {
    state = const HomeState.loading();
    try {
      final unreadAlertCountFuture = _getUnreadAlertCountBestEffort();
      final plants = await _repository.getPlants();
      if (plants.isEmpty) {
        state = HomeState(
          status: HomeStatus.empty,
          unreadAlertCount: await unreadAlertCountFuture,
        );
        return;
      }
      final selected = plants.first;
      final dashboard = await _repository.getDashboard(selected);
      state = HomeState(
        status: HomeStatus.loaded,
        plants: plants,
        selectedPlantId: selected.id,
        dashboard: dashboard,
        unreadAlertCount: await unreadAlertCountFuture,
      );
    } catch (error) {
      state = HomeState(
        status: HomeStatus.failure,
        message: _messageFor(error),
      );
    }
  }

  Future<void> selectPlant(String plantId) async {
    if (plantId == state.selectedPlantId || state.isRefreshing) {
      return;
    }
    HomePlant? selected;
    for (final plant in state.plants) {
      if (plant.id == plantId) {
        selected = plant;
        break;
      }
    }
    if (selected == null) {
      return;
    }

    state = state.copyWith(
      selectedPlantId: plantId,
      isRefreshing: true,
      message: null,
    );
    try {
      final dashboard = await _repository.getDashboard(selected);
      state = state.copyWith(
        status: HomeStatus.loaded,
        dashboard: dashboard,
        isRefreshing: false,
        message: null,
      );
    } catch (error) {
      state = state.copyWith(
        selectedPlantId: state.dashboard?.plant.id,
        isRefreshing: false,
        message: _messageFor(error),
      );
    }
  }

  Future<void> refresh() async {
    final selected = state.dashboard?.plant;
    if (selected == null || state.isRefreshing) {
      return load();
    }

    state = state.copyWith(isRefreshing: true, message: null);
    try {
      final unreadAlertCountFuture = _getUnreadAlertCountBestEffort();
      final dashboard = await _repository.getDashboard(selected);
      state = state.copyWith(
        dashboard: dashboard,
        isRefreshing: false,
        message: null,
        unreadAlertCount: await unreadAlertCountFuture,
      );
    } catch (error) {
      state = state.copyWith(isRefreshing: false, message: _messageFor(error));
    }
  }

  Future<int> _getUnreadAlertCountBestEffort() async {
    try {
      return await _repository.getUnreadAlertCount();
    } catch (_) {
      return state.unreadAlertCount;
    }
  }

  String _messageFor(Object error) {
    if (error is ApiException) {
      return switch (error.kind) {
        ApiExceptionKind.connection ||
        ApiExceptionKind.timeout => '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.',
        ApiExceptionKind.problem =>
          error.problem?.detail ?? '홈 정보를 불러오지 못했습니다.',
        ApiExceptionKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
        _ => '홈 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
      };
    }
    return '홈 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.';
  }
}
