import {
  Component,
  Input,
  OnChanges,
  OnInit,
  OnDestroy
} from '@angular/core';

import { Subscription } from 'rxjs';

import { BuildingRoom } from '../../models/building.model';
import { RealtimeService } from '../../services/realtime.service';
import {
  ActuatorService,
  ActuatorId,
  ActuatorAction
} from '../../services/actuator.service';

interface ActuatorDefinition {
  id: ActuatorId;
  label: string;
  firstAction: ActuatorAction;
  firstLabel: string;
  secondAction: ActuatorAction;
  secondLabel: string;
}

interface ActuatorEventData {
  roomId?: unknown;
  actuator?: unknown;
  state?: unknown;
}

@Component({
  selector: 'app-actuator-control',
  standalone: true,
  templateUrl: './actuator-control.html',
  styleUrl: './actuator-control.scss'
})
export class ActuatorControlComponent
  implements OnChanges, OnInit, OnDestroy {

  @Input({ required: true }) rooms: BuildingRoom[] = [];

  readonly actuators: ActuatorDefinition[] = [
    {
      id: 'VENTILATION',
      label: 'Ventilation',
      firstAction: 'ON',
      firstLabel: 'Activer',
      secondAction: 'OFF',
      secondLabel: 'Couper'
    },
    {
      id: 'SIREN',
      label: 'Sirène',
      firstAction: 'ON',
      firstLabel: 'Activer',
      secondAction: 'OFF',
      secondLabel: 'Arrêter'
    },
    {
      id: 'GAS_VALVE',
      label: 'Vanne de gaz',
      firstAction: 'OPEN',
      firstLabel: 'Ouvrir',
      secondAction: 'CLOSE',
      secondLabel: 'Fermer'
    },
    {
      id: 'EMERGENCY_EXIT',
      label: 'Sortie de secours',
      firstAction: 'OPEN',
      firstLabel: 'Ouvrir',
      secondAction: 'CLOSE',
      secondLabel: 'Fermer'
    }
  ];

  states: Record<string, Record<string, string>> = {};
  pending: Record<string, boolean> = {};
  messages: Record<string, string> = {};
  errors: Record<string, string> = {};
  loadingRooms: Record<string, boolean> = {};

  private readonly loadedRooms = new Set<string>();
  private realtimeSubscription?: Subscription;

  constructor(
    private readonly actuatorService: ActuatorService,
    private readonly realtimeService: RealtimeService
  ) {}

  ngOnChanges(): void {
    for (const room of this.rooms ?? []) {
      if (!this.loadedRooms.has(room.id)) {
        this.loadRoom(room.id);
      }
    }
  }

  ngOnInit(): void {
    this.realtimeSubscription =
      this.realtimeService.events$.subscribe(event => {
        this.handleRealtimeEvent(event);
      });
  }

  ngOnDestroy(): void {
    this.realtimeSubscription?.unsubscribe();
  }

  private key(roomId: string, actuator: ActuatorId): string {
    return `${roomId}::${actuator}`;
  }

  loadRoom(roomId: string): void {
    this.loadedRooms.add(roomId);
    this.loadingRooms = {
      ...this.loadingRooms,
      [roomId]: true
    };

    this.actuatorService.getStates(roomId).subscribe({
      next: data => {
        this.states = {
          ...this.states,
          [roomId]: data ?? {}
        };

        this.errors = {
          ...this.errors,
          [roomId]: ''
        };
      },
      error: error => {
        this.errors = {
          ...this.errors,
          [roomId]:
            error?.error?.error ??
            'Impossible de récupérer les états confirmés.'
        };
      },
      complete: () => {
        this.loadingRooms = {
          ...this.loadingRooms,
          [roomId]: false
        };
      }
    }).add(() => {
      this.loadingRooms = {
        ...this.loadingRooms,
        [roomId]: false
      };
    });
  }

  refreshRoom(roomId: string): void {
    this.loadedRooms.delete(roomId);
    this.loadRoom(roomId);
  }

  getState(roomId: string, actuator: ActuatorId): string {
    return this.states[roomId]?.[actuator] ?? '';
  }

  getStateLabel(roomId: string, actuator: ActuatorId): string {
    const state = this.getState(roomId, actuator);

    if (!state) return 'Non confirmé';
    if (state === 'ON') return 'Activé';
    if (state === 'OFF') return 'Désactivé';
    if (state === 'OPEN') return 'Ouvert';
    if (state === 'CLOSE') return 'Fermé';

    return state;
  }

  getStateClass(roomId: string, actuator: ActuatorId): string {
    const state = this.getState(roomId, actuator);

    if (state === 'ON' || state === 'OPEN') return 'state-active';
    if (state === 'OFF' || state === 'CLOSE') return 'state-inactive';

    return 'state-unknown';
  }

  isPending(roomId: string, actuator: ActuatorId): boolean {
    return this.pending[this.key(roomId, actuator)] ?? false;
  }

  sendCommand(
    roomId: string,
    actuator: ActuatorId,
    action: ActuatorAction,
    actionLabel: string
  ): void {
    const commandKey = this.key(roomId, actuator);

    if (this.isPending(roomId, actuator)) {
      return;
    }

    if (this.getState(roomId, actuator) === action) {
      this.messages = {
        ...this.messages,
        [commandKey]: 'Cet état est déjà confirmé.'
      };
      return;
    }

    const definition = this.actuators.find(item => item.id === actuator);
    const actuatorLabel = definition?.label ?? actuator;

    const confirmed = window.confirm(
      `Confirmer la commande "${actionLabel}" pour ${actuatorLabel} dans la salle ${roomId} ?`
    );

    if (!confirmed) {
      return;
    }

    this.errors = {
      ...this.errors,
      [commandKey]: ''
    };

    this.messages = {
      ...this.messages,
      [commandKey]: 'Envoi de la commande...'
    };

    this.pending = {
      ...this.pending,
      [commandKey]: true
    };

    this.actuatorService.sendCommand(roomId, actuator, action).subscribe({
      next: response => {
        if (response.status === 'COMMAND_SENT') {
          this.messages = {
            ...this.messages,
            [commandKey]:
              'Commande envoyée. En attente de confirmation de l’appareil.'
          };
        } else {
          this.messages = {
            ...this.messages,
            [commandKey]: 'Réponse du serveur reçue.'
          };
        }
      },
      error: error => {
        this.pending = {
          ...this.pending,
          [commandKey]: false
        };

        this.errors = {
          ...this.errors,
          [commandKey]:
            error?.error?.error ??
            'La commande n’a pas pu être envoyée.'
        };

        this.messages = {
          ...this.messages,
          [commandKey]: ''
        };
      }
    });
  }

  private handleRealtimeEvent(event: unknown): void {
    if (typeof event !== 'object' || event === null) {
      return;
    }

    const envelope = event as {
      kind?: unknown;
      data?: unknown;
    };

    if (envelope.kind !== 'actuator' || typeof envelope.data !== 'object' || envelope.data === null) {
      return;
    }

    const data = envelope.data as ActuatorEventData;

    if (
      typeof data.roomId !== 'string' ||
      typeof data.actuator !== 'string' ||
      typeof data.state !== 'string'
    ) {
      return;
    }

    const allowedActuators: ActuatorId[] = [
      'VENTILATION',
      'SIREN',
      'GAS_VALVE',
      'EMERGENCY_EXIT'
    ];

    if (!allowedActuators.includes(data.actuator as ActuatorId)) {
      return;
    }

    const actuator = data.actuator as ActuatorId;
    const commandKey = this.key(data.roomId, actuator);

    this.states = {
      ...this.states,
      [data.roomId]: {
        ...(this.states[data.roomId] ?? {}),
        [actuator]: data.state
      }
    };

    this.pending = {
      ...this.pending,
      [commandKey]: false
    };

    this.messages = {
      ...this.messages,
      [commandKey]: `État confirmé par l’appareil : ${this.getStateLabel(data.roomId, actuator)}.`
    };

    this.errors = {
      ...this.errors,
      [commandKey]: ''
    };
  }
}
