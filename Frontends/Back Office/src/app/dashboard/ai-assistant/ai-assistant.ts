import { Component, Input, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';

import { Incident } from '../../models/incident.model';
import {
  AiService,
  AiResponse,
  AiStats,
  AiInteraction
} from '../../services/ai.service';

interface AiMessage {
  interactionId: string;
  question: string;
  answer: string;
  toolsUsed: string[];
  model: string;
  latencyMs: number;
  comment: string;
  vote: string | null;
  feedbackSending: boolean;
  feedbackMessage: string;
}

@Component({
  selector: 'app-ai-assistant',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './ai-assistant.html',
  styleUrl: './ai-assistant.scss'
})
export class AiAssistantComponent implements OnInit {
  @Input() incidents: Incident[] = [];

  private readonly aiService = inject(AiService);

  question = '';
  roomId = '';
  selectedIncidentId = '';

  messages: AiMessage[] = [];

  sending = false;
  errorMessage = '';

  stats: AiStats | null = null;
  interactions: AiInteraction[] = [];
  monitoringError = '';
  monitoringLoading = false;

  ngOnInit(): void {
    this.refreshMonitoring();
  }

  ask(): void {
    const question = this.question.trim();

    if (!question || this.sending) {
      return;
    }

    const roomId = this.roomId.trim();

    this.executeRequest(
      question,
      this.aiService.askQuestion(question, roomId || undefined),
      () => {
        this.question = '';
      }
    );
  }

  explainSelectedIncident(): void {
    if (!this.selectedIncidentId || this.sending) {
      return;
    }

    const incident = this.incidents.find(
      item => item.id === this.selectedIncidentId
    );

    if (!incident) {
      this.errorMessage = 'Incident sélectionné introuvable.';
      return;
    }

    const prompt = `Explication de l'incident ${incident.id} - ${incident.type}`;

    this.executeRequest(
      prompt,
      this.aiService.explainIncident(incident.id)
    );
  }

  private executeRequest(
    prompt: string,
    request: Observable<AiResponse>,
    onSuccess?: () => void
  ): void {
    this.sending = true;
    this.errorMessage = '';

    request.subscribe({
      next: response => {
        this.messages = [
          ...this.messages,
          {
            interactionId: response.interactionId,
            question: prompt,
            answer: response.answer ?? '',
            toolsUsed: response.toolsUsed ?? [],
            model: response.model ?? 'Ollama',
            latencyMs: response.latencyMs ?? 0,
            comment: '',
            vote: null,
            feedbackSending: false,
            feedbackMessage: ''
          }
        ];

        onSuccess?.();
      },
      error: error => {
        const detail =
          error?.error?.error ??
          error?.error?.message ??
          error?.message;

        this.errorMessage = detail
          ? String(detail)
          : 'Le service IA est momentanément indisponible.';
      },
      complete: () => {
        this.sending = false;
      }
    }).add(() => {
      this.sending = false;
    });
  }

  submitFeedback(message: AiMessage, vote: 'UP' | 'DOWN'): void {
    if (message.feedbackSending || message.vote) {
      return;
    }

    message.feedbackSending = true;
    message.feedbackMessage = '';

    this.aiService.submitFeedback(
      message.interactionId,
      vote,
      message.comment
    ).subscribe({
      next: () => {
        message.vote = vote;
        message.feedbackMessage = 'Merci pour votre évaluation.';
        this.refreshMonitoring();
      },
      error: error => {
        message.feedbackMessage =
          error?.error?.error ??
          'Impossible d’enregistrer cette évaluation.';
      },
      complete: () => {
        message.feedbackSending = false;
      }
    }).add(() => {
      message.feedbackSending = false;
    });
  }

  refreshMonitoring(): void {
    this.monitoringLoading = true;
    this.monitoringError = '';

    let pending = 2;

    const finish = (): void => {
      pending--;

      if (pending === 0) {
        this.monitoringLoading = false;
      }
    };

    this.aiService.getStats().subscribe({
      next: data => {
        this.stats = data;
      },
      error: () => {
        this.monitoringError =
          'Impossible de charger les statistiques IA.';
        finish();
      },
      complete: finish
    });

    this.aiService.getInteractions().subscribe({
      next: data => {
        this.interactions = data ?? [];
      },
      error: () => {
        this.monitoringError =
          'Impossible de charger l’historique des interactions IA.';
        finish();
      },
      complete: finish
    });
  }
}
